import 'package:flutter/material.dart';
import 'terminal_buffer.dart';

class TerminalEmulator {
  final TermBuffer buf;
  String _esc = '';
  bool _inEsc = false;
  bool _inCsi = false;
  bool _inOsc = false;
  String _osc = '';

  TerminalEmulator({int cols = 120, int rows = 40}) : buf = TermBuffer(cols: cols, rows: rows);

  void write(String data) {
    for (int i = 0; i < data.length; i++) {
      final ch = data[i];
      if (_inOsc) {
        if (ch == '\x07' || (ch == '\\' && _osc.endsWith('\x1b'))) { _inOsc = false; _osc = ''; }
        else { _osc += ch; if (_osc.length > 4096) { _inOsc = false; _osc=''; } }
        continue;
      }
      if (_inCsi) {
        _esc += ch;
        final code = ch.codeUnitAt(0);
        if (code >= 64 && code <= 126) { _handleCsi(_esc); _inCsi = false; _inEsc = false; _esc = ''; }
        else if (_esc.length > 64) { _inCsi = false; _inEsc = false; _esc=''; }
        continue;
      }
      if (_inEsc) {
        if (ch == '[') { _inCsi = true; _esc = ''; continue; }
        if (ch == ']') { _inOsc = true; _osc = ''; _inEsc = false; continue; }
        _handleEsc(ch); _inEsc = false; _esc = ''; continue;
      }
      if (ch == '\x1b') { _inEsc = true; _esc = ''; continue; }
      buf.putChar(ch);
    }
  }

  void _handleEsc(String ch) {
    switch (ch) {
      case '7': buf.savedX = buf.cursorX; buf.savedY = buf.cursorY; break;
      case '8': buf.cursorX = buf.savedX; buf.cursorY = buf.savedY; break;
      case 'M':
        if (buf.cursorY == buf.scrollTop) { buf.screen.insert(buf.scrollTop, TLine()); if (buf.screen.length > buf.rows) buf.screen.removeAt(buf.scrollBottom+1); }
        else if (buf.cursorY > 0) buf.cursorY--; break;
      case 'D': buf.newLine(); break;
      case 'E': buf.carriageReturn(); buf.newLine(); break;
      case 'c': buf.clearAll(); buf.curAttr = const CellAttr(); break;
      default: break;
    }
  }

  List<int> _parseParams(String s) {
    final clean = s.replaceAll(RegExp(r'[^0-9;\-]'), '');
    if (clean.isEmpty) return [];
    return clean.split(';').map((e) => int.tryParse(e) ?? 0).toList();
  }

  int _pn(List<int> p, int i, int defVal) { if (i < p.length && p[i] != 0) return p[i]; return defVal; }

  void _handleCsi(String body) {
    if (body.isEmpty) return;
    final cmd = body.substring(body.length - 1);
    final raw = body.substring(0, body.length - 1);
    final priv = raw.startsWith('?');
    final params = _parseParams(raw);
    switch (cmd) {
      case 'A': buf.cursorY = (buf.cursorY - _pn(params,0,1)).clamp(0, buf.rows-1); break;
      case 'B': buf.cursorY = (buf.cursorY + _pn(params,0,1)).clamp(0, buf.rows-1); break;
      case 'C': buf.cursorX = (buf.cursorX + _pn(params,0,1)).clamp(0, buf.cols-1); break;
      case 'D': buf.cursorX = (buf.cursorX - _pn(params,0,1)).clamp(0, buf.cols-1); break;
      case 'E': buf.cursorY = (buf.cursorY + _pn(params,0,1)).clamp(0, buf.rows-1); buf.cursorX = 0; break;
      case 'F': buf.cursorY = (buf.cursorY - _pn(params,0,1)).clamp(0, buf.rows-1); buf.cursorX = 0; break;
      case 'G': buf.cursorX = (_pn(params,0,1) - 1).clamp(0, buf.cols-1); break;
      case 'H': case 'f':
        final y = ((_pn(params,0,1)) - 1).clamp(0, buf.rows-1);
        final x = ((params.length>1 ? _pn(params,1,1) : 1) - 1).clamp(0, buf.cols-1);
        buf.cursorY = y; buf.cursorX = x; break;
      case 'J': buf.eraseInDisplay(params.isEmpty?0:params[0]); break;
      case 'K': buf.eraseInLine(params.isEmpty?0:params[0]); break;
      case 'd': buf.cursorY = (_pn(params,0,1)-1).clamp(0, buf.rows-1); break;
      case 'm': _handleSgr(params.isEmpty?[0]:params); break;
      case 'r':
        final t = (_pn(params,0,1)-1).clamp(0, buf.rows-1);
        final btm = ((params.length>1 && params[1]!=0 ? params[1] : buf.rows)-1).clamp(0, buf.rows-1);
        buf.scrollTop = t; buf.scrollBottom = btm; buf.cursorX=0; buf.cursorY=0; break;
      case 's': buf.savedX = buf.cursorX; buf.savedY = buf.cursorY; break;
      case 'u': buf.cursorX = buf.savedX; buf.cursorY = buf.savedY; break;
      case 'L': for(int i=0;i<_pn(params,0,1);i++){ buf.screen.insert(buf.cursorY,TLine()); if(buf.screen.length>buf.rows) buf.screen.removeLast(); } break;
      case 'M': for(int i=0;i<_pn(params,0,1);i++){ if(buf.cursorY<buf.screen.length) buf.screen.removeAt(buf.cursorY); buf.screen.add(TLine()); } break;
      case 'P': { final n=_pn(params,0,1); final line=buf.screen[buf.cursorY]; if(buf.cursorX<line.cells.length){ final end=(buf.cursorX+n).clamp(0,line.cells.length); line.cells.removeRange(buf.cursorX,end); } } break;
      case '@': { final n=_pn(params,0,1); final line=buf.screen[buf.cursorY]; while(line.cells.length<buf.cursorX) line.cells.add(TCell(' ', const CellAttr())); for(int i=0;i<n;i++){ if(buf.cursorX<=line.cells.length) line.cells.insert(buf.cursorX, TCell(' ', buf.curAttr)); } } break;
      case 'S': for(int i=0;i<_pn(params,0,1);i++){ buf.scrollback.add(TLine()); if(buf.scrollback.length>buf.maxScrollback) buf.scrollback.removeAt(0); if(buf.screen.isNotEmpty) buf.screen.removeAt(0); buf.screen.add(TLine()); } break;
      case 'T': for(int i=0;i<_pn(params,0,1);i++){ if(buf.screen.isNotEmpty) buf.screen.removeLast(); buf.screen.insert(0,TLine()); } break;
      case 'l': case 'h':
        if (priv && raw.contains('1049')) { if (cmd=='h') buf.clearAll(); }
        break;
      default: break;
    }
  }

  static const _ansi = <Color>[Color(0xFF000000),Color(0xFFCD3131),Color(0xFF0DBC79),Color(0xFFE5E510),Color(0xFF2472C8),Color(0xFFBC3FBC),Color(0xFF11A8CD),Color(0xFFE5E5E5)];
  static const _bright = <Color>[Color(0xFF666666),Color(0xFFF14C4C),Color(0xFF23D18B),Color(0xFFF5F543),Color(0xFF3B8EEA),Color(0xFFD670D6),Color(0xFF29B8DB),Color(0xFFFFFFFF)];
  Color _x256(int n){ if(n<8) return _ansi[n]; if(n<16) return _bright[n-8]; if(n>=232){ final v=8+(n-232)*10; return Color.fromARGB(255,v,v,v);} final i=n-16; int cv(int x)=>x==0?0:55+x*40; return Color.fromARGB(255,cv((i~/36)%6),cv((i~/6)%6),cv(i%6)); }

  void _handleSgr(List<int> p) {
    for (int i=0;i<p.length;i++){
      final v=p[i];
      switch(v){
        case 0: buf.curAttr = const CellAttr(); break;
        case 1: buf.curAttr = buf.curAttr.copyWith(bold:true); break;
        case 2: buf.curAttr = buf.curAttr.copyWith(dim:true); break;
        case 3: buf.curAttr = buf.curAttr.copyWith(italic:true); break;
        case 4: buf.curAttr = buf.curAttr.copyWith(underline:true); break;
        case 7: buf.curAttr = buf.curAttr.copyWith(inverse:true); break;
        case 22: buf.curAttr = buf.curAttr.copyWith(bold:false,dim:false); break;
        case 23: buf.curAttr = buf.curAttr.copyWith(italic:false); break;
        case 24: buf.curAttr = buf.curAttr.copyWith(underline:false); break;
        case 27: buf.curAttr = buf.curAttr.copyWith(inverse:false); break;
        case 39: buf.curAttr = buf.curAttr.copyWith(fg: const Color(0xFFE6EDF3)); break;
        case 49: buf.curAttr = buf.curAttr.copyWith(bg: Colors.transparent); break;
        default:
          if(v>=30&&v<=37) buf.curAttr=buf.curAttr.copyWith(fg:_ansi[v-30]);
          else if(v>=90&&v<=97) buf.curAttr=buf.curAttr.copyWith(fg:_bright[v-90]);
          else if(v>=40&&v<=47) buf.curAttr=buf.curAttr.copyWith(bg:_ansi[v-40]);
          else if(v>=100&&v<=107) buf.curAttr=buf.curAttr.copyWith(bg:_bright[v-100]);
          else if(v==38 && i+1<p.length){ if(p[i+1]==5 && i+2<p.length){ buf.curAttr=buf.curAttr.copyWith(fg:_x256(p[i+2])); i+=2; } else if(p[i+1]==2 && i+4<p.length){ buf.curAttr=buf.curAttr.copyWith(fg:Color.fromARGB(255,p[i+2],p[i+3],p[i+4])); i+=4; } }
          else if(v==48 && i+1<p.length){ if(p[i+1]==5 && i+2<p.length){ buf.curAttr=buf.curAttr.copyWith(bg:_x256(p[i+2])); i+=2; } else if(p[i+1]==2 && i+4<p.length){ buf.curAttr=buf.curAttr.copyWith(bg:Color.fromARGB(255,p[i+2],p[i+3],p[i+4])); i+=4; } }
      }
    }
  }
}

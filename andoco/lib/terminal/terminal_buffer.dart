import 'package:flutter/material.dart';

class CellAttr {
  final Color fg;
  final Color bg;
  final bool bold;
  final bool dim;
  final bool italic;
  final bool underline;
  final bool inverse;
  const CellAttr({
    this.fg = const Color(0xFFE6EDF3),
    this.bg = Colors.transparent,
    this.bold = false,
    this.dim = false,
    this.italic = false,
    this.underline = false,
    this.inverse = false,
  });
  CellAttr copyWith({Color? fg, Color? bg, bool? bold, bool? dim, bool? italic, bool? underline, bool? inverse}) => CellAttr(
    fg: fg ?? this.fg, bg: bg ?? this.bg, bold: bold ?? this.bold, dim: dim ?? this.dim,
    italic: italic ?? this.italic, underline: underline ?? this.underline, inverse: inverse ?? this.inverse,
  );
  Color effFg() => inverse ? (bg == Colors.transparent ? const Color(0xFF0B0F14) : bg) : fg;
  Color effBg() => inverse ? fg : bg;
}

class TCell {
  String ch;
  CellAttr attr;
  TCell(this.ch, this.attr);
  TCell clone() => TCell(ch, attr);
}

class TLine {
  List<TCell> cells = [];
  bool wrapped = false;
}

class TermBuffer {
  int cols;
  int rows;
  int cursorX = 0;
  int cursorY = 0;
  int savedX = 0;
  int savedY = 0;
  int scrollTop = 0;
  int scrollBottom;
  CellAttr curAttr = const CellAttr();
  final List<TLine> screen = [];
  final List<TLine> scrollback = [];
  final int maxScrollback;

  TermBuffer({this.cols = 120, this.rows = 40, this.maxScrollback = 8000}) : scrollBottom = 39 {
    scrollBottom = rows - 1;
    _ensure();
  }

  void _ensure() {
    while (screen.length < rows) screen.add(TLine());
    if (screen.length > rows) screen.removeRange(rows, screen.length);
  }

  void resize(int c, int r) {
    cols = c.clamp(20, 1000);
    rows = r.clamp(5, 500);
    _ensure();
    cursorX = cursorX.clamp(0, cols - 1);
    cursorY = cursorY.clamp(0, rows - 1);
    scrollTop = 0; scrollBottom = rows - 1;
  }

  void clearAll() { screen.clear(); _ensure(); cursorX = 0; cursorY = 0; }
  void clearScrollback() { scrollback.clear(); }

  void eraseInDisplay(int n) {
    if (n == 2 || n == 3) { for (final l in screen) { l.cells.clear(); l.wrapped=false; } cursorX = 0; cursorY = 0; if (n==3) scrollback.clear(); return; }
    if (n == 0) { eraseInLine(0); for (int y = cursorY+1; y < rows; y++) { screen[y].cells.clear(); screen[y].wrapped=false; } }
    if (n == 1) { for (int y = 0; y < cursorY; y++) { screen[y].cells.clear(); screen[y].wrapped=false; } eraseInLine(1); }
  }

  void eraseInLine(int n) {
    final line = screen[cursorY];
    if (n == 2) { line.cells.clear(); return; }
    if (n == 0) { if (cursorX < line.cells.length) line.cells.removeRange(cursorX, line.cells.length); }
    if (n == 1) { final end = (cursorX+1).clamp(0, line.cells.length); for (int i=0;i<end;i++){ if(i<line.cells.length) line.cells[i]=TCell(' ', curAttr); } }
  }

  TLine _cloneLine(TLine l) { final n = TLine(); n.cells = l.cells.map((c)=>c.clone()).toList(); n.wrapped=l.wrapped; return n; }

  void _lineFeed() {
    if (cursorY == scrollBottom) {
      scrollback.add(_cloneLine(screen[scrollTop]));
      if (scrollback.length > maxScrollback) scrollback.removeRange(0, scrollback.length - maxScrollback);
      screen.removeAt(scrollTop);
      screen.insert(scrollBottom, TLine());
    } else if (cursorY < rows - 1) {
      cursorY++;
    }
  }

  void newLine() { _lineFeed(); }
  void carriageReturn() { cursorX = 0; }
  void backspace() { if (cursorX > 0) cursorX--; }
  void tab() { cursorX = ((cursorX ~/ 8) + 1) * 8; if (cursorX >= cols) cursorX = cols - 1; }

  void putChar(String ch) {
    if (ch == '\n') { newLine(); return; }
    if (ch == '\r') { carriageReturn(); return; }
    if (ch == '\b') { backspace(); return; }
    if (ch == '\t') { tab(); return; }
    if (ch.isEmpty) return;
    final cu = ch.codeUnitAt(0);
    if (cu < 32) return;

    if (cursorX >= cols) {
      // hard wrap at edge
      screen[cursorY].wrapped = true;
      cursorX = 0;
      _lineFeed();
    }
    final line = screen[cursorY];
    while (line.cells.length <= cursorX) line.cells.add(TCell(' ', const CellAttr()));
    line.cells[cursorX] = TCell(ch, curAttr);
    cursorX++;
  }

  void writeText(String text) { for (final g in text.characters) { putChar(g); } }

  String toPlainText({bool includeScrollback = true}) {
    final b = StringBuffer();
    final lines = includeScrollback ? [...scrollback, ...screen] : screen;
    for (final l in lines) {
      if (l.cells.isEmpty) { b.writeln(); continue; }
      final s = l.cells.map((c)=>c.ch).join();
      b.writeln(s.replaceAll(RegExp(r'\s+$'), ''));
    }
    return b.toString();
  }

  List<TLine> allLines() => [...scrollback, ...screen];
}

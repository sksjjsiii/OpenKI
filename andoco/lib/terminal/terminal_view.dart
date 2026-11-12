import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'terminal_buffer.dart';
import 'terminal_emulator.dart';

class CustomTerminalView extends StatefulWidget {
  final TerminalEmulator emulator;
  final void Function(String data) onInput;
  final double fontSize;
  const CustomTerminalView({super.key, required this.emulator, required this.onInput, this.fontSize = 13});

  @override
  State<CustomTerminalView> createState() => _CustomTerminalViewState();
}

class _CustomTerminalViewState extends State<CustomTerminalView> {
  final FocusNode _focus = FocusNode();
  final ScrollController _vScroll = ScrollController();
  bool _stickToBottom = true;
  Timer? _cursorTimer;
  bool _cursorOn = true;

  @override
  void initState() {
    super.initState();
    _vScroll.addListener(() {
      if (!_vScroll.hasClients) return;
      _stickToBottom = _vScroll.offset >= (_vScroll.position.maxScrollExtent - 30);
    });
    _cursorTimer = Timer.periodic(const Duration(milliseconds: 600), (_) {
      if (mounted) setState(() => _cursorOn = !_cursorOn);
    });
  }

  @override
  void dispose() {
    _cursorTimer?.cancel();
    _focus.dispose();
    _vScroll.dispose();
    super.dispose();
  }

  void _scrollToBottomSoon() {
    if (!_stickToBottom) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_vScroll.hasClients) _vScroll.jumpTo(_vScroll.position.maxScrollExtent);
    });
  }

  TextStyle _baseStyle() => TextStyle(
        fontFamily: 'monospace',
        fontFamilyFallback: const [
          'Noto Sans Mono',
          'DejaVu Sans Mono',
          'Noto Naskh Arabic',
          'Noto Sans Arabic',
          'Courier New',
          'sans-serif',
        ],
        fontSize: widget.fontSize,
        height: 1.3,
        color: const Color(0xFFE6EDF3),
      );

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) return KeyEventResult.ignored;
    final k = event.logicalKey;
    final ctrl = HardwareKeyboard.instance.isControlPressed;
    if (k == LogicalKeyboardKey.enter || k == LogicalKeyboardKey.numpadEnter) { widget.onInput('\r'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.backspace) { widget.onInput('\x7f'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.tab) { widget.onInput('\t'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.escape) { widget.onInput('\x1b'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.arrowUp) { widget.onInput('\x1b[A'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.arrowDown) { widget.onInput('\x1b[B'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.arrowRight) { widget.onInput('\x1b[C'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.arrowLeft) { widget.onInput('\x1b[D'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.home) { widget.onInput('\x1b[H'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.end) { widget.onInput('\x1b[F'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.delete) { widget.onInput('\x1b[3~'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.pageUp) { widget.onInput('\x1b[5~'); return KeyEventResult.handled; }
    if (k == LogicalKeyboardKey.pageDown) { widget.onInput('\x1b[6~'); return KeyEventResult.handled; }
    if (ctrl) {
      final ch = event.character;
      if (ch != null && ch.isNotEmpty) {
        final c = ch.toUpperCase().codeUnitAt(0);
        if (c >= 64 && c <= 95) { widget.onInput(String.fromCharCode(c - 64)); return KeyEventResult.handled; }
      }
    }
    final ch = event.character;
    if (ch != null && ch.isNotEmpty) { widget.onInput(ch); return KeyEventResult.handled; }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    _scrollToBottomSoon();
    return LayoutBuilder(builder: (context, constraints) {
      final style = _baseStyle();
      final tp = TextPainter(text: TextSpan(text: 'MMMMMMMMMM', style: style), textDirection: TextDirection.ltr, maxLines: 1)..layout();
      final charW = (tp.width / 10).clamp(4.0, 40.0);
      final lineH = tp.height.clamp(10.0, 60.0);
      final cols = math.max(20, ((constraints.maxWidth - 16) / charW).floor());
      final viewRows = math.max(5, (constraints.maxHeight / lineH).ceil() + 2);
      if (widget.emulator.buf.cols != cols) {
        widget.emulator.buf.resize(cols, viewRows);
      } else {
        widget.emulator.buf.rows = viewRows.clamp(5, 500);
      }

      final all = widget.emulator.buf.allLines();
      final screenStart = widget.emulator.buf.scrollback.length;
      return Focus(
        focusNode: _focus,
        autofocus: true,
        onKeyEvent: _onKey,
        child: GestureDetector(
          onTap: () => _focus.requestFocus(),
          behavior: HitTestBehavior.opaque,
          child: Container(
            color: const Color(0xFF0B0F14),
            child: Scrollbar(
              controller: _vScroll,
              thumbVisibility: true,
              child: ListView.builder(
                controller: _vScroll,
                padding: const EdgeInsets.all(8),
                itemCount: all.length + 1,
                itemBuilder: (context, i) {
                  if (i == all.length) return const SizedBox(height: 80);
                  return _buildLine(all[i], i, screenStart);
                },
              ),
            ),
          ),
        ),
      );
    });
  }

  Widget _buildLine(TLine l, int globalIndex, int screenStart) {
    final isScreen = globalIndex >= screenStart;
    final screenRow = globalIndex - screenStart;
    final showCursor = isScreen && screenRow == widget.emulator.buf.cursorY && _cursorOn && _focus.hasFocus;
    final cursorX = widget.emulator.buf.cursorX;
    final style = _baseStyle();

    // Build spans always in logical order, render strictly LTR/left-aligned.
    final spans = <InlineSpan>[];
    if (l.cells.isEmpty) {
      if (showCursor) {
        spans.add(WidgetSpan(alignment: PlaceholderAlignment.baseline, baseline: TextBaseline.alphabetic, child: Container(width: 8, height: widget.fontSize * 1.3, color: const Color(0xFFE6EDF3))));
      } else {
        spans.add(TextSpan(text: ' ', style: style));
      }
    } else {
      final limit = (showCursor && cursorX >= l.cells.length) ? cursorX + 1 : l.cells.length;
      for (int i = 0; i < limit; i++) {
        final cell = i < l.cells.length ? l.cells[i] : TCell(' ', const CellAttr());
        final txt = cell.ch.isEmpty ? ' ' : cell.ch;
        if (showCursor && i == cursorX) {
          spans.add(WidgetSpan(
            alignment: PlaceholderAlignment.baseline,
            baseline: TextBaseline.alphabetic,
            child: Container(
              color: const Color(0xFFE6EDF3),
              child: Text(txt, style: _styleFor(cell.attr).copyWith(color: const Color(0xFF0B0F14), backgroundColor: const Color(0xFFE6EDF3))),
            ),
          ));
        } else {
          spans.add(TextSpan(text: txt, style: _styleFor(cell.attr)));
        }
      }
    }

    // Wrap enabled for safety, but buffer already wraps at cols so lines should fit; no horizontal scroll.
    return Directionality(
      textDirection: TextDirection.ltr,
      child: Align(
        alignment: Alignment.centerLeft,
        child: SelectableText.rich(
          TextSpan(children: spans),
          textAlign: TextAlign.left,
          textDirection: TextDirection.ltr,
          style: style,
        ),
      ),
    );
  }

  TextStyle _styleFor(CellAttr a) => _baseStyle().copyWith(
        color: a.effFg(),
        backgroundColor: a.effBg() == Colors.transparent ? null : a.effBg(),
        fontWeight: a.bold ? FontWeight.w700 : FontWeight.w400,
        fontStyle: a.italic ? FontStyle.italic : FontStyle.normal,
        decoration: a.underline ? TextDecoration.underline : TextDecoration.none,
      );
}

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:xterm/xterm.dart';
import '../services/opencode_bridge.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final OpencodeBridge _bridge = OpencodeBridge();
  late final WebViewController _webController;
  late final Terminal _terminal;
  StreamSubscription<String>? _logSub;
  bool _bootstrapped = false;
  bool _serverReady = false;
  bool _showTerminal = true;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _terminal = Terminal(maxLines: 10000);
    _webController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF000000));

    _logSub = _bridge.logStream.listen((line) {
      final text = line.endsWith('\n') ? line : '$line\r\n';
      _terminal.write(text);
      if (!_serverReady &&
          (line.contains('127.0.0.1:4096') ||
              line.toLowerCase().contains('listening') ||
              line.toLowerCase().contains('server started'))) {
        _onServerReady();
      }
    });

    _init();
  }

  Future<void> _init() async {
    await _bridge.startService();
    final boot = await _bridge.isBootstrapped();
    final ready = await _bridge.isServerReady();
    if (!mounted) return;
    setState(() {
      _bootstrapped = boot;
      _serverReady = ready;
      _loading = false;
    });
    if (ready) _loadWebView();
    _pollReady();
  }

  void _pollReady() async {
    for (int i = 0; i < 300; i++) {
      await Future.delayed(const Duration(seconds: 2));
      if (!mounted || _serverReady) return;
      final ready = await _bridge.isServerReady();
      final boot = await _bridge.isBootstrapped();
      if (!mounted) return;
      if (boot != _bootstrapped) setState(() => _bootstrapped = boot);
      if (ready) {
        _onServerReady();
        return;
      }
    }
  }

  void _onServerReady() {
    if (_serverReady) return;
    setState(() {
      _serverReady = true;
      _bootstrapped = true;
    });
    _loadWebView();
  }

  void _loadWebView() {
    _webController.loadRequest(Uri.parse('http://127.0.0.1:4096'));
  }

  Future<void> _reload() async {
    await _bridge.reload();
    _loadWebView();
  }

  @override
  void dispose() {
    _logSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Andoco'),
        actions: [
          IconButton(
            icon: Icon(_showTerminal ? Icons.terminal : Icons.web),
            tooltip: _showTerminal ? 'Hide terminal' : 'Show terminal',
            onPressed: () => setState(() => _showTerminal = !_showTerminal),
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                if (!_bootstrapped)
                  const LinearProgressIndicator(),
                Expanded(
                  flex: _showTerminal ? 3 : 1,
                  child: _serverReady
                      ? WebViewWidget(controller: _webController)
                      : Center(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Text(
                              _bootstrapped
                                  ? 'Starting OpenCode server...'
                                  : 'Bootstrapping Debian environment (first run)...',
                              textAlign: TextAlign.center,
                            ),
                          ),
                        ),
                ),
                if (_showTerminal)
                  const Divider(height: 1),
                if (_showTerminal)
                  Expanded(
                    flex: 2,
                    child: Container(
                      color: Colors.black,
                      child: TerminalView(_terminal),
                    ),
                  ),
              ],
            ),
      floatingActionButton: FloatingActionButton(
        onPressed: _reload,
        tooltip: 'Reload',
        child: const Icon(Icons.refresh),
      ),
    );
  }
}

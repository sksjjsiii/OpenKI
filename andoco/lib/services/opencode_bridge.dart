import 'dart:async';
import 'package:flutter/services.dart';

class LogEvent {
  final String session;
  final String line;
  LogEvent(this.session, this.line);
}

class OpencodeBridge {
  static const MethodChannel _control = MethodChannel('opencode/control');
  static const EventChannel _logs = EventChannel('opencode/logs');

  Stream<LogEvent>? _logStream;

  Stream<LogEvent> get logStream {
    _logStream ??= _logs.receiveBroadcastStream().map((event) {
      if (event is Map) {
        return LogEvent(event['session']?.toString() ?? 'main', event['line']?.toString() ?? '');
      }
      return LogEvent('main', event?.toString() ?? '');
    });
    return _logStream!;
  }

  Future<void> startService() => _control.invokeMethod('startService');
  Future<void> stopService() => _control.invokeMethod('stopService');
  Future<void> reload() => _control.invokeMethod('reload');
  Future<bool> isBootstrapped() async => await _control.invokeMethod<bool>('isBootstrapped') ?? false;
  Future<bool> isServerReady() async => await _control.invokeMethod<bool>('isServerReady') ?? false;
  Future<String> getStatus() async => await _control.invokeMethod<String>('getStatus') ?? '';
  Future<void> sendInput(String input, {String session = 'main'}) => _control.invokeMethod('sendInput', {'input': input, 'session': session});
  Future<String> createSession() async => await _control.invokeMethod<String>('createSession') ?? 'main';
  Future<void> closeSession(String session) => _control.invokeMethod('closeSession', {'session': session});
  Future<List<String>> listSessions() async {
    final r = await _control.invokeMethod<List>('listSessions');
    return r?.map((e) => e.toString()).toList() ?? ['main'];
  }
}

import 'dart:async';
import 'package:flutter/services.dart';

class OpencodeBridge {
  static const MethodChannel _control = MethodChannel('opencode/control');
  static const EventChannel _logs = EventChannel('opencode/logs');

  Stream<String>? _logStream;

  Stream<String> get logStream {
    _logStream ??= _logs
        .receiveBroadcastStream()
        .map((event) => event?.toString() ?? '');
    return _logStream!;
  }

  Future<void> startService() => _control.invokeMethod('startService');
  Future<void> stopService() => _control.invokeMethod('stopService');
  Future<void> reload() => _control.invokeMethod('reload');
  Future<bool> isBootstrapped() async =>
      await _control.invokeMethod<bool>('isBootstrapped') ?? false;
  Future<bool> isServerReady() async =>
      await _control.invokeMethod<bool>('isServerReady') ?? false;
  Future<void> sendInput(String input) =>
      _control.invokeMethod('sendInput', {'input': input});
}

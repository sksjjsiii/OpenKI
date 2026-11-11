import 'package:flutter/material.dart';
import 'screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const AndocoApp());
}

class AndocoApp extends StatelessWidget {
  const AndocoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Andoco',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true),
      home: const HomeScreen(),
    );
  }
}

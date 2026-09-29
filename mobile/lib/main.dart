import 'package:flutter/material.dart';
import 'package:infortts_shared/infortts_shared.dart';

void main() {
  runApp(const CharniaApp());
}

class CharniaApp extends StatelessWidget {
  const CharniaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Charnia by Infortts',
      debugShowCheckedModeBanner: false,
      theme: AcousticTheme.darkTheme,
      home: InforttsAppShell(
        appName: 'Charnia by Infortts',
        appDescription: 'Corporate registry & governance dispatcher',
        appVersion: '1.2.0',
        workspaceChild: InforttsNodeWorkspace(
          appName: 'Charnia',
          tagline: 'Corporate registry & governance dispatcher',
          packageId: 'com.infortts.charnia',
        ),
      ),
    );
  }
}

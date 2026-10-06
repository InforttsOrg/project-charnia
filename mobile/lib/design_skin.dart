// GENERATED FILE — DO NOT EDIT BY HAND.
// Regenerate: python3 tools/design-pipeline/generate.py
// Source skill: ~/.config/opencode/skills/vintage/DESIGN.md
//
//   Design : Vintage
//   Mode   : dark
//   Skill  : vintage
//
// Audit at generation time (WCAG on the generated panel surface):
//   body contrast  15.17:1   subtext contrast 6.35:1   accent 3.26:1
//
// The Infortts 3D emblem, logo and shell chrome are NOT affected by this file —
// they stay canonical Rocky Vision. Only the product surface is restyled, which
// is how each app ends up visually distinct while remaining on-brand.
//
// Prefer composing `shared` primitives (AcousticSurface, AcousticButton,
// AcousticText, AcousticSection…) over hand-rolled decoration; they read these
// values automatically.

import 'package:flutter/material.dart';

import 'package:infortts_shared/infortts_shared.dart';

/// Design skin for **charnia/mobile**.
class AppDesignSkin {
  const AppDesignSkin._();

  static const String appName = 'charnia/mobile';
  static const String skill = 'vintage';
  static const String designName = 'Vintage';
  static const bool isDark = true;

  /// Apply before `runApp`.
  static void boot({Brightness brightness = Brightness.dark}) {
    InforttsDesign.boot(skin, initialBrightness: brightness);
  }

  static const AcousticDynamicThemeConfig skin = AcousticDynamicThemeConfig(
    // accents
    primaryColor: Color(0xFF047368),
    secondaryColor: Color(0xFFC89DA1),
    primaryDimColor: Color(0xFF04312E),
    successColor: Color(0xFF16A34A),
    dangerColor: Color(0xFFDC2626),
    warnColor: Color(0xFFD97706),

    // surfaces
    darkBg: Color(0xFF050B0F),
    darkPanelBg: Color(0xFF041515),
    lightBg: Color(0xFFC0C0C0),
    lightPanelBg: Color(0xFFC0C0C0),
    obsidianColor: Color(0xFF051316),
    activeCardColor: Color(0xFF3E4C4E),

    // text (dark palette)
    titaniumColor: Color(0xFFE2E8F0),
    steelColor: Color(0xFF8E989D),
    midGrayColor: Color(0xFF3E4C4E),
    // Drives AcousticColors.lightOnBackground/lightOnSurface, so it must be
    // the LIGHT palette's ink — the dark titanium would be invisible on a light
    // panel. Light-mode subtext reuses steelColor via lightOnSurfaceVariant.
    textOnSurface: Color(0xFF000000),
    lightSubTextColor: Color(0xFF565656),
    outlineColor: Color(0xFF3E4C4E),

    // type
    fontFamily: 'Silkscreen',
    monoFamily: 'JetBrains Mono',
    displayFamily: 'Silkscreen',
    bodyTextSize: 16,
    headingTextSize: 32,

    // geometry
    cardBorderRadius: 8,
    radiusSm: 4,
  );
}


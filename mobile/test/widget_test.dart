import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:charnia/main.dart';

void main() {
  testWidgets('Charnia by Infortts shell renders dashboard and settings tabs', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({
      'infortts_auth_userId': 'test-user',
      'infortts_auth_email': 'test@infortts.site',
      'infortts_auth_profile': '{"name":"Test User"}',
    });

    await tester.pumpWidget(const CharniaApp());
    await tester.pump(const Duration(milliseconds: 2600));

    expect(find.text('Dashboard'), findsOneWidget);
    expect(find.text('Settings'), findsOneWidget);

    await tester.tap(find.text('Settings'));
    await tester.pump();

    expect(find.textContaining('SETTINGS & CONFIGURATION'), findsOneWidget);
  });
}

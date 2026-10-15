// Новый интерфейс DroidDesk на телефоне: вкладки Device / Permissions / Settings
// и служебные настройки по PIN. Главные экраны ничего не умеют выключать —
// всё, что может сломать аренду, спрятано за PIN (см. ТЗ).
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:provider/provider.dart';

import '../../common.dart';
import '../../models/platform_model.dart';
import '../../models/server_model.dart';
import 'home_page.dart';
import 'server_page.dart';
import 'settings_page.dart';

/// PIN служебных настроек. Один на весь парк; поменять перед раскаткой.
const String kDdServicePin = '1234';

// Только тёмная тема.
const _bg = Color(0xFF0B0F17); // фон страниц
const _surface = Color(0xFF151B26); // карточки
const _surface2 = Color(0xFF1F2733); // кнопки/клавиши на карточках
const _ink = Color(0xFFE8ECF3); // основной текст
const _ink2 = Color(0xFFB4BDCB);
const _muted = Color(0xFF8A94A6);
const _faint = Color(0xFF5E6878); // недоступное
const _line = Color(0xFF232B38);
const _border = Color(0xFF2C3544);
const _blue = Color(0xFF3B82F6);
const _blueSoft = Color(0xFF16233D);
const _green = Color(0xFF34C38F);
const _greenBtn = Color(0xFF1E9E6F);
const _amber = Color(0xFFF5A524);
const _amberSoft = Color(0xFF2E2410);
const _orange = Color(0xFFF97316);
const _orangeBtn = Color(0xFFC2410C);
const _red = Color(0xFFF26B5B);
const _redBtn = Color(0xFFD13A2A);
const _redLine = Color(0xFF5A2A2A);

// ---------------------------------------------------------------------------
// Данные
// ---------------------------------------------------------------------------

/// Состояние телефона, которое Flutter сам не знает: зарядка, сеть, экономия
/// батареи, показ поверх окон. Берётся из MainActivity.dd_health раз в 3 с.
class DdHealth extends ChangeNotifier {
  static final DdHealth instance = DdHealth._();
  DdHealth._();

  bool charging = false;
  int battery = -1;
  bool ignoringBattery = true;
  bool overlays = true;
  bool accessibility = true;
  bool projectMedia = false;
  String network = 'other';
  String manufacturer = '';
  String model = '';
  String release = '';
  String deviceName = '';
  Timer? _timer;

  void start() {
    _timer ??= Timer.periodic(const Duration(seconds: 3), (_) => refresh());
    refresh();
  }

  Future<void> refresh() async {
    try {
      final r = await platformFFI.invokeMethod('dd_health');
      if (r is! Map) return;
      charging = r['charging'] == true;
      battery = (r['batteryLevel'] as num?)?.toInt() ?? -1;
      ignoringBattery = r['ignoringBatteryOptimizations'] != false;
      overlays = r['canDrawOverlays'] != false;
      accessibility = r['accessibility'] != false;
      projectMedia = r['projectMedia'] == true;
      network = (r['network'] as String?) ?? 'other';
      manufacturer = (r['manufacturer'] as String?) ?? '';
      model = (r['model'] as String?) ?? '';
      release = (r['release'] as String?) ?? '';
      deviceName = (r['deviceName'] as String?) ?? '';
      notifyListeners();
    } catch (e) {
      debugPrint('dd_health: $e');
    }
  }
}

final Map<int, DateTime> _sessionStart = {};

List<Client> ddActiveClients(ServerModel m) => m.clients
    .where((c) =>
        c.authorized && !c.disconnected && !c.isFileTransfer && !c.isTerminal)
    .toList();

enum DdKind { stopped, permission, noInternet, offline, session, warning, ready }

/// MediaProjection активна только пока подключён арендатор, поэтому её
/// отсутствие вне сессии — не ошибка. Ошибкой считаем только выключенный
/// Accessibility (без него нет управления и XML-режима).
bool _remoteOk(ServerModel m, DdHealth h) => h.accessibility;

/// Захват экрана годен, если он уже идёт, выбран XML/скриншоты (MP не нужна)
/// или выдан appop PROJECT_MEDIA (стартует без диалога при подключении).
bool _captureOk(ServerModel m, DdHealth h) =>
    m.mediaOk || m.captureMethod != 'mp' || h.projectMedia;

/// (текст, цвет) состояния захвата экрана.
(String, Color) _captureState(ServerModel m, DdHealth h) {
  if (m.mediaOk) return ('Active', _green);
  if (m.captureMethod == 'xml') return ('XML mode', _green);
  if (m.captureMethod == 'shot') return ('Screenshots', _green);
  if (h.projectMedia) return ('Starts on connect', _green);
  return ('Asks on connect', _amber);
}

const _kCaptureChannel = MethodChannel('com.carriez.flutter_hbb/capture');

/// Переключить режим картинки. Во время работы сервиса — на лету
/// (switchMethod), иначе только запоминаем выбор на стороне Android.
Future<void> ddSetCaptureMode(ServerModel m, String mode) async {
  if (m.captureMethod == mode) return;
  if (m.isStart) {
    await m.switchCaptureMethod(mode);
  } else {
    await _kCaptureChannel.invokeMethod(captureChannelMethod(mode));
    m.onNativeCaptureMethod(mode);
  }
}

DdKind ddKind(ServerModel m, DdHealth h) {
  if (!m.isStart) return DdKind.stopped;
  if (!_remoteOk(m, h)) return DdKind.permission;
  if (h.network == 'none') return DdKind.noInternet;
  if (m.connectStatus <= 0) return DdKind.offline;
  if (ddActiveClients(m).isNotEmpty) return DdKind.session;
  if (!h.ignoringBattery || !h.charging) return DdKind.warning;
  return DdKind.ready;
}

String _modeName(String m) =>
    {'mp': 'Media Projection', 'xml': 'XML', 'shot': 'Screenshots'}[m] ?? 'Media Projection';

String _dur(Duration d) {
  if (d.inHours > 0) return '${d.inHours} h ${d.inMinutes % 60} min';
  return '${d.inMinutes} min';
}

void _goTab(int i) => HomePage.homeKey.currentState?.selectTab(i);

/// Экраны DroidDesk всегда в тёмной теме, независимо от системной.
Widget ddDark(Widget child) => Theme(data: MyTheme.darkTheme, child: child);

Future<void> _openSettings(String target) =>
    platformFFI.invokeMethod('dd_open_settings', target);

/// Спросить PIN и открыть служебные настройки.
Future<void> ddOpenService(BuildContext context, String reason) async {
  final ok = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => ddDark(DdPinPage(reason: reason))));
  if (ok == true && context.mounted) {
    Navigator.of(context)
        .push(MaterialPageRoute(builder: (_) => ddDark(const DdServicePage())));
  }
}

// ---------------------------------------------------------------------------
// Общие элементы
// ---------------------------------------------------------------------------

Widget _card({required Widget child, EdgeInsets? padding, Color? color}) =>
    Container(
      decoration: BoxDecoration(
          color: color ?? _surface,
          borderRadius: BorderRadius.circular(20)),
      padding: padding ?? const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
      child: child,
    );

Widget _sectionTitle(String t) => Padding(
      padding: const EdgeInsets.fromLTRB(4, 8, 4, 0),
      child: Text(t.toUpperCase(),
          style: const TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: _muted,
              letterSpacing: 0.6)),
    );

Widget _value(String text, Color color) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        const SizedBox(width: 8),
        Text(text,
            style: TextStyle(
                fontSize: 15, fontWeight: FontWeight.w600, color: color)),
      ],
    );

Widget _kv(String k, String v) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(k, style: const TextStyle(fontSize: 15, color: _muted)),
          const SizedBox(width: 12),
          Flexible(
              child: Text(v,
                  textAlign: TextAlign.right,
                  style: const TextStyle(fontSize: 15, color: _ink))),
        ],
      ),
    );

class _Page extends StatelessWidget {
  final List<Widget> children;
  final Color background;
  const _Page({required this.children, this.background = _bg});

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Container(
      color: background,
      child: SafeArea(
        bottom: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          children: [
            for (var i = 0; i < children.length; i++) ...[
              if (i > 0) const SizedBox(height: 14),
              children[i],
            ]
          ],
        ),
      ),
    ));
  }
}

Widget _pageTitle(String t) => Padding(
      padding: const EdgeInsets.fromLTRB(2, 6, 2, 0),
      child: Text(t,
          style: const TextStyle(
              fontSize: 26, fontWeight: FontWeight.w700, color: _ink)),
    );

/// Подписка на модель сервера и на DdHealth одновременно.
class _Watch extends StatelessWidget {
  final Widget Function(BuildContext, ServerModel, DdHealth) builder;
  const _Watch({required this.builder});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider.value(
      value: gFFI.serverModel,
      child: AnimatedBuilder(
        animation: DdHealth.instance,
        builder: (context, _) => Consumer<ServerModel>(
            builder: (context, m, _) => builder(context, m, DdHealth.instance)),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Вкладка Device
// ---------------------------------------------------------------------------

class DdDevicePage extends StatefulWidget implements PageShape {
  DdDevicePage({Key? key}) : super(key: key);
  @override
  final title = 'Device';
  @override
  final icon = const Icon(Icons.smartphone_outlined);
  @override
  final appBarActions = const <Widget>[];

  @override
  State<DdDevicePage> createState() => _DdDevicePageState();
}

class _DdDevicePageState extends State<DdDevicePage> {
  bool _checking = false;
  bool _checked = false;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    // Тикаем раз в 30 с, чтобы «Duration» сессии обновлялась.
    _tick = Timer.periodic(const Duration(seconds: 30), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  Future<void> _check() async {
    if (_checking) return;
    setState(() {
      _checking = true;
      _checked = false;
    });
    checkService();
    await gFFI.serverModel.fetchID();
    await DdHealth.instance.refresh();
    await Future.delayed(const Duration(milliseconds: 1200));
    if (mounted) {
      setState(() {
        _checking = false;
        _checked = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return _Watch(builder: (context, m, h) {
      final kind = ddKind(m, h);
      final children = <Widget>[
        const Padding(
          padding: EdgeInsets.fromLTRB(2, 6, 2, 0),
          child: Text.rich(TextSpan(children: [
            TextSpan(text: 'Droid', style: TextStyle(color: _ink)),
            TextSpan(text: 'Desk', style: TextStyle(color: _blue)),
          ]),
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 30, fontWeight: FontWeight.w700, letterSpacing: -0.5)),
        ),
        _deviceCard(m, h),
        _modeCard(m),
      ];
      if (kind == DdKind.session) {
        children.addAll(_sessionCards(m));
      } else {
        children.addAll([
          _statusCard(context, kind),
          _checklist(m, h),
          _keepCard(),
        ]);
      }
      return _Page(children: children);
    });
  }

  Widget _deviceCard(ServerModel m, DdHealth h) {
    final name = h.deviceName.isNotEmpty
        ? h.deviceName
        : '${h.manufacturer} ${h.model}'.trim();
    final sub = [
      if (h.model.isNotEmpty) h.model,
      if (h.release.isNotEmpty) 'Android ${h.release}'
    ].join(' · ');
    final id = m.serverId.value.text.trim();
    return _card(
      padding: const EdgeInsets.all(16),
      child: Row(children: [
        Container(
          width: 52,
          height: 92,
          decoration: BoxDecoration(
              color: const Color(0xFF05070B),
              border: Border.all(color: _border),
              borderRadius: BorderRadius.circular(9)),
          padding: const EdgeInsets.fromLTRB(4, 8, 4, 8),
          child: Container(
              decoration: BoxDecoration(
                  color: _blue.withOpacity(0.45),
                  borderRadius: BorderRadius.circular(4))),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(name.isEmpty ? 'This phone' : name,
                  style: const TextStyle(
                      fontSize: 18, fontWeight: FontWeight.w600, color: _ink)),
              if (sub.isNotEmpty)
                Text(sub, style: const TextStyle(fontSize: 14, color: _muted)),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.fromLTRB(12, 6, 4, 6),
                decoration: BoxDecoration(
                    border: Border.all(color: _border),
                    borderRadius: BorderRadius.circular(12)),
                child: Row(children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('Device ID',
                            style: TextStyle(fontSize: 12, color: _muted)),
                        Text(id.isEmpty ? '…' : id,
                            style: const TextStyle(
                                fontSize: 17,
                                fontWeight: FontWeight.w600,
                                color: _ink,
                                letterSpacing: 0.3)),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: 'Copy device ID',
                    icon: const Icon(Icons.copy_outlined, size: 20, color: _muted),
                    onPressed: id.isEmpty
                        ? null
                        : () {
                            Clipboard.setData(
                                ClipboardData(text: id.replaceAll(' ', '')));
                            showToast('Device ID copied');
                          },
                  ),
                ]),
              ),
            ],
          ),
        ),
      ]),
    );
  }

  Widget _modeCard(ServerModel m) {
    Widget btn(String mode, String label, String hint, IconData ic) {
      final on = m.captureMethod == mode;
      return SizedBox(
        height: 68,
        child: ElevatedButton(
          style: ElevatedButton.styleFrom(
              backgroundColor: on ? _blue : _surface2,
              foregroundColor: on ? Colors.white : _ink,
              elevation: 0,
              side: BorderSide(color: on ? _blue : _border),
              padding: const EdgeInsets.symmetric(horizontal: 16),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
          onPressed: () => ddSetCaptureMode(m, mode),
          child: Row(children: [
            Icon(ic, size: 26),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(label, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 2),
                  Text(hint,
                      style: TextStyle(
                          fontSize: 12.5,
                          fontWeight: FontWeight.w400,
                          color: on ? Colors.white70 : _muted)),
                ],
              ),
            ),
            if (on) const Icon(Icons.check_circle_rounded, size: 22),
          ]),
        ),
      );
    }

    return _card(
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        const Text('Picture mode',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
        const SizedBox(height: 12),
        btn('mp', 'Media Projection mode', 'Real screen video', Icons.videocam_outlined),
        const SizedBox(height: 10),
        btn('xml', 'XML mode', 'Drawn from the screen layout, sees protected apps',
            Icons.account_tree_outlined),
        if (m.captureMethod != 'mp') ...[
          const SizedBox(height: 10),
          const Text('Lasts one session, then switches back to Media Projection.',
              style: TextStyle(fontSize: 13, height: 1.4, color: _muted)),
        ],
      ]),
    );
  }

  Widget _statusCard(BuildContext context, DdKind kind) {
    late Color bg, border, icon, btn;
    late IconData glyph;
    late String title, body;
    String action = '';
    VoidCallback? onAction;
    switch (kind) {
      case DdKind.stopped:
        bg = const Color(0xFF2A1517);
        border = _redLine;
        icon = _red;
        btn = _redBtn;
        glyph = Icons.stop_rounded;
        title = 'Service stopped';
        body = 'This phone isn’t accepting connections.';
        action = 'Start';
        onAction = () => gFFI.serverModel.toggleService();
        break;
      case DdKind.permission:
        bg = const Color(0xFF2A1C10);
        border = const Color(0xFF5C3A1A);
        icon = _orange;
        btn = _orangeBtn;
        glyph = Icons.priority_high_rounded;
        title = 'Permission needed';
        body = 'Remote control is off, so renters can’t tap or type on this phone.';
        action = 'Fix';
        onAction = () => _goTab(1);
        break;
      case DdKind.noInternet:
        bg = const Color(0xFF2A1C10);
        border = const Color(0xFF5C3A1A);
        icon = _orange;
        btn = _orangeBtn;
        glyph = Icons.wifi_off_rounded;
        title = 'No internet';
        body = 'Check Wi‑Fi or the mobile network.';
        action = 'Check connection';
        onAction = _check;
        break;
      case DdKind.offline:
        bg = const Color(0xFF2A1C10);
        border = const Color(0xFF5C3A1A);
        icon = _orange;
        btn = _orangeBtn;
        glyph = Icons.cloud_off_rounded;
        title = 'Can’t reach DroidDesk';
        body = 'The internet works, but the DroidDesk server isn’t answering yet.';
        action = 'Check connection';
        onAction = _check;
        break;
      case DdKind.warning:
        bg = _amberSoft;
        border = const Color(0xFF5A4818);
        icon = _amber;
        btn = const Color(0xFFB7791F);
        glyph = Icons.priority_high_rounded;
        title = 'Ready, with a warning';
        body = DdHealth.instance.ignoringBattery
            ? 'The phone isn’t charging. Keep it plugged in during rentals.'
            : 'Battery saver is on, so Android may close DroidDesk in the background.';
        action = 'Details';
        onAction = () => _goTab(1);
        break;
      default:
        bg = const Color(0xFF0F2A20);
        border = const Color(0xFF1F5A41);
        icon = _greenBtn;
        btn = _greenBtn;
        glyph = Icons.check_rounded;
        title = 'Device is ready';
        body = 'Everything is set up. This phone can be rented.';
    }
    return Container(
      decoration: BoxDecoration(
          color: bg,
          border: Border.all(color: border),
          borderRadius: BorderRadius.circular(20)),
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(color: icon, shape: BoxShape.circle),
              child: Icon(glyph, color: Colors.white, size: 28),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: const TextStyle(
                          fontSize: 21, fontWeight: FontWeight.w700, color: _ink)),
                  const SizedBox(height: 6),
                  Text(body,
                      style: const TextStyle(
                          fontSize: 15, height: 1.45, color: _ink2)),
                ],
              ),
            ),
          ]),
          if (action.isNotEmpty) ...[
            const SizedBox(height: 14),
            SizedBox(
              height: 48,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                    backgroundColor: btn,
                    foregroundColor: Colors.white,
                    elevation: 0,
                    shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12))),
                onPressed: onAction,
                child: Text(action,
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w600)),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _row(String label, Widget value, {VoidCallback? onTap, bool last = false}) {
    final row = Container(
      constraints: const BoxConstraints(minHeight: 52),
      decoration: BoxDecoration(
          border: last ? null : const Border(bottom: BorderSide(color: _line))),
      child: Row(children: [
        Expanded(
            child: Text(label,
                style: const TextStyle(fontSize: 15, color: _ink))),
        value,
        if (onTap != null) ...[
          const SizedBox(width: 6),
          const Icon(Icons.chevron_right_rounded, size: 20, color: _muted),
        ],
      ]),
    );
    return onTap == null ? row : InkWell(onTap: onTap, child: row);
  }

  Widget _checklist(ServerModel m, DdHealth h) {
    final conn = !m.isStart
        ? _value('Offline', _red)
        : m.connectStatus > 0
            ? _value('Online', _green)
            : m.connectStatus == 0
                ? _value('Connecting', _amber)
                : _value('Offline', _red);
    final access = _remoteOk(m, h)
        ? _value('Ready', _green)
        : _value('Needs permission', _amber);
    final (capText, capColor) = _captureState(m, h);
    final capture = _value(capText, capColor);
    final power = h.charging
        ? _value('Charging', _green)
        : _value(h.battery >= 0 ? 'On battery, ${h.battery}%' : 'On battery', _amber);
    final net = {
      'wifi': _value('Wi‑Fi', _green),
      'cellular': _value('Mobile network', _green),
      'ethernet': _value('Ethernet', _green),
      'none': _value('No internet', _red),
    }[h.network] ?? _value('Connected', _green);
    final bgw = h.ignoringBattery
        ? _value('Allowed', _green)
        : _value('Battery saver on', _amber);
    return _card(
      padding: const EdgeInsets.fromLTRB(18, 6, 18, 18),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        _row('Connected to DroidDesk', conn),
        _row('Remote control', access, onTap: () => _goTab(1)),
        _row('Screen capture', capture),
        _row('Power', power),
        _row('Internet', net),
        _row('Background work', bgw, onTap: () => _goTab(1), last: true),
        const SizedBox(height: 14),
        SizedBox(
          height: 52,
          child: ElevatedButton(
            style: ElevatedButton.styleFrom(
                backgroundColor: _blue,
                foregroundColor: Colors.white,
                elevation: 0,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14))),
            onPressed: _check,
            child: Text(_checking ? 'Checking…' : 'Check connection',
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          ),
        ),
        if (_checked && !_checking)
          const Padding(
            padding: EdgeInsets.only(top: 10),
            child: Text('Checked just now',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 13, color: _muted)),
          ),
      ]),
    );
  }

  Widget _keepCard() => _card(
        child: const Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Keep this phone',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: _ink)),
            SizedBox(height: 8),
            Text('• Plugged in to power', style: TextStyle(fontSize: 14, color: _ink2)),
            SizedBox(height: 4),
            Text('• Connected to the internet', style: TextStyle(fontSize: 14, color: _ink2)),
            SizedBox(height: 4),
            Text('• With DroidDesk running', style: TextStyle(fontSize: 14, color: _ink2)),
          ],
        ),
      );

  List<Widget> _sessionCards(ServerModel m) {
    final clients = ddActiveClients(m);
    final c = clients.first;
    final started = _sessionStart.putIfAbsent(c.id, () => DateTime.now());
    final dur = DateTime.now().difference(started);
    final hh = started.hour % 12 == 0 ? 12 : started.hour % 12;
    final startedText =
        '$hh:${started.minute.toString().padLeft(2, '0')} ${started.hour < 12 ? 'AM' : 'PM'}';
    Widget onOff(String label, bool on) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
            Text(label, style: const TextStyle(fontSize: 15, color: _ink)),
            Text(on ? 'On' : 'Off',
                style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: on ? _green : _muted)),
          ]),
        );
    return [
      Container(
        decoration: BoxDecoration(
            color: _blueSoft,
            border: Border.all(color: const Color(0xFF24406E)),
            borderRadius: BorderRadius.circular(20)),
        padding: const EdgeInsets.all(18),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Container(
                width: 10,
                height: 10,
                decoration: const BoxDecoration(color: _green, shape: BoxShape.circle)),
            const SizedBox(width: 8),
            const Expanded(
                child: Text('Session in progress',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink))),
            Text(_dur(dur), style: const TextStyle(fontSize: 13, color: _ink2)),
          ]),
          const SizedBox(height: 16),
          Row(children: [
            Container(
              width: 52,
              height: 52,
              decoration: const BoxDecoration(color: _surface2, shape: BoxShape.circle),
              child: const Icon(Icons.person_outline_rounded, color: _blue, size: 28),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(c.name.isEmpty ? 'Renter' : c.name,
                    style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: _ink)),
                Text(clients.length > 1 ? 'and ${clients.length - 1} more connected' : 'Connected via DroidDesk',
                    style: const TextStyle(fontSize: 14, color: _ink2)),
              ]),
            ),
          ]),
          const SizedBox(height: 14),
          onOff('Screen view', true),
          onOff('Remote control', true),
          onOff('File transfer', m.fileOk),
          onOff('Clipboard', m.clipboardOk),
        ]),
      ),
      _card(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const Text('Session details',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
          const SizedBox(height: 8),
          _kv('Started', startedText),
          _kv('Duration', _dur(dur)),
          _kv('Connection', 'DroidDesk server'),
          _kv('Picture mode', _modeName(m.captureMethod)),
        ]),
      ),
    ];
  }
}

// ---------------------------------------------------------------------------
// Вкладка Permissions
// ---------------------------------------------------------------------------

class DdPermissionsPage extends StatelessWidget implements PageShape {
  DdPermissionsPage({Key? key}) : super(key: key);
  @override
  final title = 'Permissions';
  @override
  final icon = const Icon(Icons.verified_user_outlined);
  @override
  final appBarActions = const <Widget>[];

  @override
  Widget build(BuildContext context) {
    return _Watch(builder: (context, m, h) {
      Widget item(IconData ic, String title, String desc, bool ok,
          {bool managed = false, Widget? status}) {
        Widget right;
        if (status != null) {
          right = status;
        } else if (managed) {
          right = Text(ok ? 'On' : 'Off',
              style: TextStyle(
                  fontSize: 14, fontWeight: FontWeight.w600, color: ok ? _green : _muted));
        } else if (ok) {
          right = const Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.check_rounded, size: 18, color: _green),
            SizedBox(width: 4),
            Text('Enabled',
                style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: _green)),
          ]);
        } else {
          right = TextButton.icon(
            style: TextButton.styleFrom(
                backgroundColor: _amberSoft,
                foregroundColor: _amber,
                minimumSize: const Size(0, 36),
                padding: const EdgeInsets.symmetric(horizontal: 12),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
            onPressed: () => ddOpenService(
                context, 'Granting system permissions is for staff only.'),
            icon: const Icon(Icons.lock_outline_rounded, size: 16),
            label: const Text('Fix', style: TextStyle(fontWeight: FontWeight.w600)),
          );
        }
        return _card(
          padding: const EdgeInsets.all(16),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                  color: _blueSoft, borderRadius: BorderRadius.circular(12)),
              child: Icon(ic, size: 20, color: _blue),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Expanded(
                      child: Text(title,
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.w600, color: _ink))),
                  right,
                ]),
                const SizedBox(height: 4),
                Text(desc, style: const TextStyle(fontSize: 14, height: 1.45, color: _muted)),
                if (managed)
                  TextButton(
                    style: TextButton.styleFrom(
                        padding: EdgeInsets.zero,
                        minimumSize: const Size(0, 32),
                        foregroundColor: _blue),
                    onPressed: () => _goTab(2),
                    child: const Text('Change in Settings',
                        style: TextStyle(fontWeight: FontWeight.w600)),
                  ),
              ]),
            ),
          ]),
        );
      }

      return _Page(children: [
        _pageTitle('Permissions'),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 4),
          child: Text(
              'What DroidDesk is allowed to do on this phone. Granting system permissions needs the service PIN.',
              style: TextStyle(fontSize: 14, height: 1.4, color: _muted)),
        ),
        item(Icons.desktop_windows_outlined, 'Screen capture',
            'Lets renters see this phone’s screen. Turns on only while a renter is connected.',
            _captureOk(m, h),
            status: Builder(builder: (_) {
              final (t, c) = _captureState(m, h);
              return Text(t,
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: c));
            })),
        item(Icons.touch_app_outlined, 'Remote control',
            'Lets renters tap, type and swipe on this phone.', h.accessibility),
        item(Icons.layers_outlined, 'Display over other apps',
            'Needed for the rental cover screen.', h.overlays),
        item(Icons.battery_std_outlined, 'Background work',
            'Stops Android from closing DroidDesk in the background.', h.ignoringBattery),
        item(Icons.folder_outlined, 'File transfer',
            'Lets renters send files to this phone and download them.', m.fileOk,
            managed: true),
        item(Icons.content_paste_outlined, 'Clipboard',
            'Lets text be copied between devices.', m.clipboardOk,
            managed: true),
      ]);
    });
  }
}

// ---------------------------------------------------------------------------
// Вкладка Settings
// ---------------------------------------------------------------------------

class DdSettingsPage extends StatelessWidget implements PageShape {
  DdSettingsPage({Key? key}) : super(key: key);
  @override
  final title = 'Settings';
  @override
  final icon = const Icon(Icons.settings_outlined);
  @override
  final appBarActions = const <Widget>[];

  @override
  Widget build(BuildContext context) {
    return _Watch(builder: (context, m, h) {
      final session = ddActiveClients(m).isNotEmpty;
      Widget toggle(String label, String desc, bool value, VoidCallback onChanged,
          {bool last = false}) {
        return Container(
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
              border: last ? null : const Border(bottom: BorderSide(color: _line))),
          child: Row(children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(label,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
                const SizedBox(height: 3),
                Text(desc, style: const TextStyle(fontSize: 13, height: 1.4, color: _muted)),
              ]),
            ),
            const SizedBox(width: 12),
            Switch(
              value: value,
              activeColor: Colors.white,
              activeTrackColor: _greenBtn,
              inactiveThumbColor: _muted,
              inactiveTrackColor: _surface2,
              onChanged: (_) => onChanged(),
            ),
          ]),
        );
      }

      return _Page(children: [
        _pageTitle('Settings'),
        _sectionTitle('General'),
        _card(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 4),
          child: InkWell(
            onTap: () => Navigator.of(context)
                .push(MaterialPageRoute(builder: (_) => ddDark(const DdLanguagePage()))),
            child: const SizedBox(
              height: 60,
              child: Row(children: [
                Icon(Icons.language_rounded, color: _blue),
                SizedBox(width: 12),
                Expanded(
                    child: Text('Language',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink))),
                Text('English', style: TextStyle(fontSize: 15, color: _muted)),
                SizedBox(width: 6),
                Icon(Icons.chevron_right_rounded, color: _muted),
              ]),
            ),
          ),
        ),
        _sectionTitle('Sharing'),
        _card(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 4),
          child: Column(children: [
            toggle('File transfer', 'Let renters send files to this phone and download them.',
                m.fileOk, () => m.toggleFile()),
            toggle('Clipboard', 'Let text be copied between this phone and the renter’s computer.',
                m.clipboardOk, () => m.toggleClipboard(),
                last: true),
          ]),
        ),
        _sectionTitle('About'),
        _card(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          child: Column(children: [
            _kv('Device ID', m.serverId.value.text.trim()),
            FutureBuilder<PackageInfo>(
              future: PackageInfo.fromPlatform(),
              builder: (context, snap) => _kv('App version',
                  snap.hasData ? '${snap.data!.version} (build ${snap.data!.buildNumber})' : version),
            ),
          ]),
        ),
        _sectionTitle('Service'),
        _card(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 4),
          child: InkWell(
            onTap: session
                ? null
                : () => ddOpenService(context, 'Service settings are for staff only.'),
            child: SizedBox(
              height: 64,
              child: Row(children: [
                Icon(Icons.lock_outline_rounded, color: session ? _faint : _amber),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Service settings',
                          style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                              color: session ? _faint : _ink)),
                      Text(session ? 'Unavailable during a session' : 'For staff. PIN required.',
                          style: const TextStyle(fontSize: 13, color: _muted)),
                    ],
                  ),
                ),
                if (!session)
                  const Icon(Icons.chevron_right_rounded, color: _muted),
              ]),
            ),
          ),
        ),
      ]);
    });
  }
}

class DdLanguagePage extends StatelessWidget {
  const DdLanguagePage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    const langs = [
      ['English', true],
      ['Русский', false],
      ['Español', false],
      ['Português', false],
      ['Deutsch', false],
      ['Français', false],
    ];
    return Scaffold(
      backgroundColor: _bg,
      appBar: AppBar(
          backgroundColor: _bg,
          foregroundColor: _ink,
          elevation: 0,
          title: const Text('Language', style: TextStyle(fontWeight: FontWeight.w700))),
      body: ListView(padding: const EdgeInsets.all(20), children: [
        _card(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 4),
          child: Column(children: [
            for (var i = 0; i < langs.length; i++)
              Container(
                height: 54,
                decoration: BoxDecoration(
                    border: i == langs.length - 1
                        ? null
                        : const Border(bottom: BorderSide(color: _line))),
                child: Row(children: [
                  Expanded(
                      child: Text(langs[i][0] as String,
                          style: TextStyle(
                              fontSize: 16,
                              color: langs[i][1] == true ? _ink : _faint))),
                  if (i == 0)
                    const Icon(Icons.check_rounded, color: _blue)
                  else
                    const Text('Coming soon',
                        style: TextStyle(fontSize: 13, color: _faint)),
                ]),
              ),
          ]),
        ),
      ]),
    );
  }
}

// ---------------------------------------------------------------------------
// PIN
// ---------------------------------------------------------------------------

class DdPinPage extends StatefulWidget {
  final String reason;
  const DdPinPage({Key? key, required this.reason}) : super(key: key);

  @override
  State<DdPinPage> createState() => _DdPinPageState();
}

class _DdPinPageState extends State<DdPinPage> {
  String _pin = '';
  bool _error = false;

  void _press(String d) {
    if (_pin.length >= 4) return;
    setState(() {
      _pin += d;
      _error = false;
    });
    if (_pin.length < 4) return;
    if (_pin == kDdServicePin) {
      Navigator.of(context).pop(true);
      return;
    }
    setState(() => _error = true);
    Future.delayed(const Duration(milliseconds: 700), () {
      if (mounted) setState(() => _pin = '');
    });
  }

  @override
  Widget build(BuildContext context) {
    final col = _error ? _red : _ink;
    Widget key(String label, {VoidCallback? onTap, bool blank = false}) => SizedBox(
          height: 60,
          child: blank
              ? (onTap == null
                  ? const SizedBox.shrink()
                  : TextButton(
                      onPressed: onTap,
                      child: const Icon(Icons.backspace_outlined, color: _ink)))
              : ElevatedButton(
                  style: ElevatedButton.styleFrom(
                      backgroundColor: _surface2,
                      foregroundColor: _ink,
                      elevation: 0,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
                  onPressed: onTap,
                  child: Text(label, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w500)),
                ),
        );
    return Scaffold(
      backgroundColor: _bg,
      appBar: AppBar(
        backgroundColor: _bg,
        foregroundColor: _ink,
        elevation: 0,
        leading: IconButton(
            tooltip: 'Cancel',
            icon: const Icon(Icons.close_rounded),
            onPressed: () => Navigator.of(context).pop(false)),
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Text('Enter service PIN',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: _ink)),
            const SizedBox(height: 6),
            Text(widget.reason,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 15, color: _muted)),
            const SizedBox(height: 24),
            Row(mainAxisSize: MainAxisSize.min, children: [
              for (var i = 0; i < 4; i++)
                Container(
                  margin: const EdgeInsets.symmetric(horizontal: 8),
                  width: 14,
                  height: 14,
                  decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(color: col, width: 2),
                      color: i < _pin.length ? col : Colors.transparent),
                ),
            ]),
            const SizedBox(height: 12),
            SizedBox(
              height: 20,
              child: Text(_error ? 'Wrong PIN' : '',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: _red)),
            ),
            const SizedBox(height: 12),
            SizedBox(
              width: 280,
              child: GridView.count(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                crossAxisCount: 3,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
                childAspectRatio: 1.45,
                children: [
                  for (final d in ['1', '2', '3', '4', '5', '6', '7', '8', '9'])
                    key(d, onTap: () => _press(d)),
                  key('', blank: true),
                  key('0', onTap: () => _press('0')),
                  key('', blank: true, onTap: () => setState(() {
                        if (_pin.isNotEmpty) _pin = _pin.substring(0, _pin.length - 1);
                        _error = false;
                      })),
                ],
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Служебные настройки (только по PIN)
// ---------------------------------------------------------------------------

class DdServicePage extends StatefulWidget {
  const DdServicePage({Key? key}) : super(key: key);

  @override
  State<DdServicePage> createState() => _DdServicePageState();
}

class _DdServicePageState extends State<DdServicePage> {
  Timer? _idle;

  @override
  void initState() {
    super.initState();
    _bump();
  }

  /// Автовыход через 2 минуты без действий.
  void _bump() {
    _idle?.cancel();
    _idle = Timer(const Duration(minutes: 2), () {
      if (mounted) Navigator.of(context).maybePop();
    });
  }

  @override
  void dispose() {
    _idle?.cancel();
    super.dispose();
  }

  Future<bool> _confirm(String title, String body, String cta) async {
    final r = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: _surface,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w700, color: _ink)),
        content: Text(body, style: const TextStyle(height: 1.45, color: _ink2)),
        actions: [
          TextButton(
              style: TextButton.styleFrom(foregroundColor: _ink2),
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('Cancel')),
          TextButton(
              style: TextButton.styleFrom(foregroundColor: _red),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: Text(cta, style: const TextStyle(fontWeight: FontWeight.w600))),
        ],
      ),
    );
    return r == true;
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: (_) => _bump(),
      child: Scaffold(
        backgroundColor: _bg,
        appBar: AppBar(
          backgroundColor: _bg,
          foregroundColor: _ink,
          elevation: 0,
          title: const Text('Service settings', style: TextStyle(fontWeight: FontWeight.w700)),
          actions: [
            Container(
              alignment: Alignment.center,
              margin: const EdgeInsets.only(right: 16, top: 14, bottom: 14),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                  color: _amberSoft, borderRadius: BorderRadius.circular(8)),
              child: const Text('STAFF ONLY',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: _amber)),
            ),
          ],
        ),
        body: _Watch(builder: (context, m, h) {
          final clients = ddActiveClients(m);
          Widget grantRow(String name, bool ok, VoidCallback grant) => SizedBox(
                height: 48,
                child: Row(children: [
                  Expanded(child: Text(name, style: const TextStyle(fontSize: 15, color: _ink))),
                  if (ok)
                    const Text('Granted',
                        style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: _green))
                  else
                    ElevatedButton(
                      style: ElevatedButton.styleFrom(
                          backgroundColor: _blue,
                          foregroundColor: Colors.white,
                          elevation: 0,
                          minimumSize: const Size(0, 36),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
                      onPressed: grant,
                      child: const Text('Grant'),
                    ),
                ]),
              );
          return ListView(padding: const EdgeInsets.fromLTRB(20, 8, 20, 28), children: [
            _card(
              child: Row(children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    const Text('Service',
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
                    Text(m.isStart ? 'Running' : 'Stopped',
                        style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: m.isStart ? _green : _red)),
                  ]),
                ),
                m.isStart
                    ? OutlinedButton(
                        style: OutlinedButton.styleFrom(
                            backgroundColor: Colors.transparent,
                            foregroundColor: _red,
                            side: const BorderSide(color: _redLine),
                            minimumSize: const Size(0, 44),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))),
                        onPressed: () async {
                          if (await _confirm('Stop the service?',
                              'The phone will stop accepting connections. An active rental session ends immediately.',
                              'Stop')) {
                            await m.stopService();
                          }
                        },
                        child: const Text('Stop service'),
                      )
                    : ElevatedButton(
                        style: ElevatedButton.styleFrom(
                            backgroundColor: _greenBtn,
                            foregroundColor: Colors.white,
                            elevation: 0,
                            minimumSize: const Size(0, 44),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))),
                        onPressed: () => m.toggleService(),
                        child: const Text('Start service'),
                      ),
              ]),
            ),
            const SizedBox(height: 12),
            _card(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                const Text('System permissions',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
                const SizedBox(height: 4),
                grantRow('Screen capture', _captureOk(m, h), () {
                  if (!m.isStart) {
                    m.toggleService();
                  } else {
                    platformFFI.invokeMethod('init_service');
                  }
                }),
                grantRow('Remote control', h.accessibility, () => _openSettings('accessibility')),
                grantRow('Display over other apps', h.overlays, () => _openSettings('overlay')),
                grantRow('Background work', h.ignoringBattery, () => _openSettings('battery')),
              ]),
            ),
            const SizedBox(height: 12),
            _card(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                const Text('Picture mode',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
                const SizedBox(height: 12),
                SegmentedButton<String>(
                  showSelectedIcon: false,
                  style: ButtonStyle(
                    backgroundColor: MaterialStateProperty.resolveWith((st) =>
                        st.contains(MaterialState.selected) ? _blue : _surface2),
                    foregroundColor: MaterialStateProperty.resolveWith((st) =>
                        st.contains(MaterialState.selected) ? Colors.white : _ink),
                    side: const MaterialStatePropertyAll(BorderSide(color: _border)),
                    textStyle: const MaterialStatePropertyAll(
                        TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                  ),
                  segments: const [
                    ButtonSegment(value: 'mp', label: Text('MP')),
                    ButtonSegment(value: 'xml', label: Text('XML')),
                    ButtonSegment(value: 'shot', label: Text('Screenshots')),
                  ],
                  selected: {m.captureMethod},
                  onSelectionChanged: (s) => ddSetCaptureMode(m, s.first),
                ),
                const SizedBox(height: 10),
                const Text('XML and Screenshots last one session, then switch back to Media Projection.',
                    style: TextStyle(fontSize: 13, height: 1.45, color: _muted)),
              ]),
            ),
            const SizedBox(height: 12),
            _card(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                const Text('Connected clients',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
                const SizedBox(height: 8),
                if (clients.isEmpty)
                  const Text('No one is connected.', style: TextStyle(fontSize: 14, color: _muted)),
                for (final c in clients)
                  SizedBox(
                    height: 48,
                    child: Row(children: [
                      Expanded(
                          child: Text(c.name.isEmpty ? c.peerId : c.name,
                              style: const TextStyle(fontSize: 15, color: _ink))),
                      OutlinedButton(
                        style: OutlinedButton.styleFrom(
                            backgroundColor: Colors.transparent,
                            foregroundColor: _red,
                            side: const BorderSide(color: _redLine),
                            minimumSize: const Size(0, 40),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
                        onPressed: () async {
                          if (await _confirm('Disconnect ${c.name.isEmpty ? 'this client' : c.name}?',
                              'They lose access to this phone right away.', 'Disconnect')) {
                            await bind.cmCloseConnection(connId: c.id);
                          }
                        },
                        child: const Text('Disconnect'),
                      ),
                    ]),
                  ),
              ]),
            ),
            const SizedBox(height: 12),
            _card(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                const Text('Technical info',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: _ink)),
                const SizedBox(height: 6),
                _kv('RustDesk ID', m.serverId.value.text.trim()),
                _kv('Server', bind.mainGetOptionSync(key: 'custom-rendezvous-server')),
                FutureBuilder<PackageInfo>(
                  future: PackageInfo.fromPlatform(),
                  builder: (context, snap) => _kv('App version',
                      snap.hasData ? '${snap.data!.version} (build ${snap.data!.buildNumber})' : version),
                ),
              ]),
            ),
            const SizedBox(height: 12),
            _card(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 4),
              child: Column(children: [
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Advanced settings',
                      style: TextStyle(fontWeight: FontWeight.w600, color: _ink)),
                  subtitle: const Text('Original RustDesk settings', style: TextStyle(color: _muted)),
                  trailing: const Icon(Icons.chevron_right_rounded, color: _muted),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => ddDark(Scaffold(
                          appBar: AppBar(title: const Text('RustDesk settings')),
                          body: SettingsPage())))),
                ),
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Original RustDesk screen',
                      style: TextStyle(fontWeight: FontWeight.w600, color: _ink)),
                  subtitle: const Text('Old connection page, for troubleshooting', style: TextStyle(color: _muted)),
                  trailing: const Icon(Icons.chevron_right_rounded, color: _muted),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => ddDark(Scaffold(
                          appBar: AppBar(title: const Text('RustDesk')),
                          body: ServerPage())))),
                ),
              ]),
            ),
            const SizedBox(height: 12),
            SizedBox(
              height: 52,
              child: ElevatedButton(
                style: ElevatedButton.styleFrom(
                    backgroundColor: _surface2,
                    foregroundColor: _ink,
                    elevation: 0,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14))),
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Exit service settings',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              ),
            ),
            const SizedBox(height: 10),
            const Text('Closes automatically after 2 minutes of inactivity.',
                textAlign: TextAlign.center, style: TextStyle(fontSize: 13, color: _muted)),
          ]);
        }),
      ),
    );
  }
}

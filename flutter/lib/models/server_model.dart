import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

import 'package:flutter/material.dart';
import 'package:flutter_hbb/consts.dart';
import 'package:flutter_hbb/main.dart';
import 'package:flutter_hbb/mobile/pages/settings_page.dart';
import 'package:flutter_hbb/models/chat_model.dart';
import 'package:flutter_hbb/models/platform_model.dart';
import 'package:flutter_hbb/utils/server_config_util.dart';
import 'package:get/get.dart';
import 'package:window_manager/window_manager.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'package:web_socket_channel/status.dart' as status;
import 'package:http/http.dart' as http;

import '../common.dart';
import '../common/formatter/id_formatter.dart';
import '../desktop/pages/server_page.dart' as desktop;
import '../desktop/widgets/tabbar_widget.dart';
import '../mobile/pages/server_page.dart';
import 'model.dart';

const kLoginDialogTag = "LOGIN";

const kUseTemporaryPassword = "use-temporary-password";
const kUsePermanentPassword = "use-permanent-password";
const kUseBothPasswords = "use-both-passwords";

class ServerModel with ChangeNotifier {
  bool _isStart = false; // Android MainService status
  bool _mediaOk = false;
  bool _inputOk = false;
  bool _audioOk = false;
  bool _fileOk = false;
  bool _clipboardOk = false;
  bool _showElevation = false;
  bool hideCm = false;
  int _connectStatus = 0; // Rendezvous Server status

  // Capture method: 'mp' = MediaProjection, 'xml' = XML/Accessibility
  String _captureMethod = 'mp';
  String get captureMethod => _captureMethod;

  // Input Control — после первого включения блокируем переключатель
  // Пользователь включил вручную через Accessibility Settings — не сбрасываем
  bool _inputEverEnabled = false;
  bool get inputEverEnabled => _inputEverEnabled;

  static const _captureChannel =
      MethodChannel('com.carriez.flutter_hbb/capture');

  // WebSocket related fields
  WebSocketChannel? _wsChannel;
  String? _jwtToken;
  String? _currentSessionId;
  Timer? _authTimer;
  Timer? _reconnectTimer;
  Timer? _heartbeatTimer;
  bool _isConnecting = false;
  bool _isAuthenticated = false;
  bool _userStoppedService = false;
  Timer? _serviceMonitorTimer;
  bool _serviceMonitoringEnabled = false;
  static const String AUTH_URL = "https://ws.mobirent.io/auth";
  static const String WS_URL = "wss://ws.mobirent.io/ws";
  static const Duration RECONNECT_DELAY = Duration(seconds: 5);
  static const Duration AUTH_REFRESH_INTERVAL = Duration(minutes: 30);
  static const Duration HEARTBEAT_INTERVAL = Duration(seconds: 30);

  String _verificationMethod = "";
  String _temporaryPasswordLength = "";
  bool _allowNumericOneTimePassword = false;
  String _approveMode = "";
  int _zeroClientLengthCounter = 0;

  late String _emptyIdShow;
  late final IDTextEditingController _serverId;
  final _serverPasswd =
      TextEditingController(text: translate("Generating ..."));

  final tabController = DesktopTabController(tabType: DesktopTabType.cm);

  final List<Client> _clients = [];

  Timer? cmHiddenTimer;

  final _wakelockKey = UniqueKey();

  bool get isStart => _isStart;

  bool get mediaOk => _mediaOk;

  bool get inputOk => _inputOk;

  bool get audioOk => _audioOk;

  bool get fileOk => _fileOk;

  bool get clipboardOk => _clipboardOk;

  bool get showElevation => _showElevation;

  int get connectStatus => _connectStatus;

  bool get isWebSocketConnected => _wsChannel != null && _isAuthenticated;

  String get verificationMethod {
    final index = [
      kUseTemporaryPassword,
      kUsePermanentPassword,
      kUseBothPasswords
    ].indexOf(_verificationMethod);
    if (index < 0) {
      return kUseBothPasswords;
    }
    return _verificationMethod;
  }

  String get approveMode => _approveMode;

  setVerificationMethod(String method) async {
    await bind.mainSetOption(key: kOptionVerificationMethod, value: method);
    /*
    if (method != kUsePermanentPassword) {
      await bind.mainSetOption(
          key: 'allow-hide-cm', value: bool2option('allow-hide-cm', false));
    }
    */
  }

  String get temporaryPasswordLength {
    final lengthIndex = ["6", "8", "10"].indexOf(_temporaryPasswordLength);
    if (lengthIndex < 0) {
      return "6";
    }
    return _temporaryPasswordLength;
  }

  setTemporaryPasswordLength(String length) async {
    await bind.mainSetOption(key: "temporary-password-length", value: length);
  }

  setApproveMode(String mode) async {
    await bind.mainSetOption(key: kOptionApproveMode, value: mode);
    /*
    if (mode != 'password') {
      await bind.mainSetOption(
          key: 'allow-hide-cm', value: bool2option('allow-hide-cm', false));
    }
    */
  }

  bool get allowNumericOneTimePassword => _allowNumericOneTimePassword;
  switchAllowNumericOneTimePassword() async {
    await mainSetBoolOption(
        kOptionAllowNumericOneTimePassword, !_allowNumericOneTimePassword);
  }

  TextEditingController get serverId => _serverId;

  TextEditingController get serverPasswd => _serverPasswd;

  List<Client> get clients => _clients;

  final controller = ScrollController();

  WeakReference<FFI> parent;

  ServerModel(this.parent) {
    _emptyIdShow = translate("Generating ...");
    _serverId = IDTextEditingController(text: _emptyIdShow);

    /*
    // initital _hideCm at startup
    final verificationMethod =
        bind.mainGetOptionSync(key: kOptionVerificationMethod);
    final approveMode = bind.mainGetOptionSync(key: kOptionApproveMode);
    _hideCm = option2bool(
        'allow-hide-cm', bind.mainGetOptionSync(key: 'allow-hide-cm'));
    if (!(approveMode == 'password' &&
        verificationMethod == kUsePermanentPassword)) {
      _hideCm = false;
    }
    */

    timerCallback() async {
      final connectionStatus =
          jsonDecode(await bind.mainGetConnectStatus()) as Map<String, dynamic>;
      final statusNum = connectionStatus['status_num'] as int;
      if (statusNum != _connectStatus) {
        _connectStatus = statusNum;
        notifyListeners();
      }

      if (desktopType == DesktopType.cm) {
        final res = await bind.cmCheckClientsLength(length: _clients.length);
        if (res != null) {
          debugPrint("clients not match!");
          updateClientState(res);
        } else {
          if (_clients.isEmpty) {
            hideCmWindow();
            if (_zeroClientLengthCounter++ == 12) {
              // 6 second
              windowManager.close();
            }
          } else {
            _zeroClientLengthCounter = 0;
            if (!hideCm) showCmWindow();
          }
        }
      }

      updatePasswordModel();
    }

    if (!isTest) {
      Future.delayed(Duration.zero, () async {
        if (await bind.optionSynced()) {
          await timerCallback();
        }
        // Загружаем сохранённый метод захвата
        await _loadCaptureMethod();
      });
      Timer.periodic(Duration(milliseconds: 500), (timer) async {
        await timerCallback();
      });
    }

    // Initial keyboard status is off on mobile
    if (isMobile) {
      bind.mainSetOption(key: kOptionEnableKeyboard, value: 'N');
    }
  }

  /// 1. check android permission
  /// 2. check config
  /// audio: permanently OFF (RECORD_AUDIO permission removed from manifest)
  /// file true by default (if permission on)
  checkAndroidPermission() async {
    // Audio capture is permanently disabled in the rental fleet build.
    // RECORD_AUDIO was removed from AndroidManifest.xml, MainService skips
    // AudioRecord init, and the toggle in the UI is hidden. Force the option
    // to 'N' on every launch so any stale 'Y' from older installs gets cleared.
    _audioOk = false;
    await bind.mainSetOption(key: kOptionEnableAudio, value: "N");

    // file
    if (!await AndroidPermissionManager.check(kManageExternalStorage)) {
      _fileOk = false;
      bind.mainSetOption(key: kOptionEnableFileTransfer, value: "N");
    } else {
      final fileOption =
          await bind.mainGetOption(key: kOptionEnableFileTransfer);
      _fileOk = fileOption != 'N';
    }

    // clipboard
    final clipOption = await bind.mainGetOption(key: kOptionEnableClipboard);
    _clipboardOk = clipOption != 'N';

    notifyListeners();
  }

  updatePasswordModel() async {
    var update = false;
    final temporaryPassword = await bind.mainGetTemporaryPassword();
    final verificationMethod =
        await bind.mainGetOption(key: kOptionVerificationMethod);
    final temporaryPasswordLength =
        await bind.mainGetOption(key: "temporary-password-length");
    final approveMode = await bind.mainGetOption(key: kOptionApproveMode);
    final numericOneTimePassword =
        await mainGetBoolOption(kOptionAllowNumericOneTimePassword);
    /*
    var hideCm = option2bool(
        'allow-hide-cm', await bind.mainGetOption(key: 'allow-hide-cm'));
    if (!(approveMode == 'password' &&
        verificationMethod == kUsePermanentPassword)) {
      hideCm = false;
    }
    */
    if (_approveMode != approveMode) {
      _approveMode = approveMode;
      update = true;
    }
    var stopped = await mainGetBoolOption(kOptionStopService);
    final oldPwdText = _serverPasswd.text;
    if (stopped ||
        verificationMethod == kUsePermanentPassword ||
        _approveMode == 'click') {
      _serverPasswd.text = '-';
    } else {
      if (_serverPasswd.text != temporaryPassword &&
          temporaryPassword.isNotEmpty) {
        _serverPasswd.text = temporaryPassword;
      }
    }
    if (oldPwdText != _serverPasswd.text) {
      update = true;
    }
    if (_verificationMethod != verificationMethod) {
      _verificationMethod = verificationMethod;
      update = true;
    }
    if (_temporaryPasswordLength != temporaryPasswordLength) {
      if (_temporaryPasswordLength.isNotEmpty) {
        bind.mainUpdateTemporaryPassword();
      }
      _temporaryPasswordLength = temporaryPasswordLength;
      update = true;
    }
    if (_allowNumericOneTimePassword != numericOneTimePassword) {
      _allowNumericOneTimePassword = numericOneTimePassword;
      update = true;
    }
    /*
    if (_hideCm != hideCm) {
      _hideCm = hideCm;
      if (desktopType == DesktopType.cm) {
        if (hideCm) {
          await hideCmWindow();
        } else {
          await showCmWindow();
        }
      }
      update = true;
    }
    */
    if (update) {
      notifyListeners();
    }
  }

  toggleAudio() async {
    // Audio capture permanently disabled (RECORD_AUDIO not in manifest).
    // Keep the function as a no-op so any stale UI/MethodChannel callers
    // don't crash; force-persist 'N' for good measure.
    _audioOk = false;
    await bind.mainSetOption(key: kOptionEnableAudio, value: 'N');
    notifyListeners();
  }

  toggleFile() async {
    if (clients.isNotEmpty) {
      await showClientsMayNotBeChangedAlert(parent.target);
    }
    if (!_fileOk &&
        !await AndroidPermissionManager.check(kManageExternalStorage)) {
      final res =
          await AndroidPermissionManager.request(kManageExternalStorage);
      if (!res) {
        showToast(translate('Failed'));
        return;
      }
    }

    _fileOk = !_fileOk;
    bind.mainSetOption(
        key: kOptionEnableFileTransfer,
        value: _fileOk ? defaultOptionYes : 'N');
    notifyListeners();
  }

  toggleClipboard() async {
    _clipboardOk = !clipboardOk;
    bind.mainSetOption(
        key: kOptionEnableClipboard,
        value: clipboardOk ? defaultOptionYes : 'N');
    notifyListeners();
  }

  toggleInput() async {
    if (clients.isNotEmpty) {
      await showClientsMayNotBeChangedAlert(parent.target);
    }
    if (_inputOk) {
      // Если уже включён — не даём выключить через UI (замок)
      // Пользователь должен идти в Accessibility Settings
      if (_inputEverEnabled) {
        showInputLockedAlert(parent.target);
        return;
      }
      parent.target?.invokeMethod("stop_input");
      bind.mainSetOption(key: kOptionEnableKeyboard, value: 'N');
    } else {
      if (parent.target != null) {
        showInputWarnAlert(parent.target!);
      }
    }
  }

  Future<bool> checkRequestNotificationPermission() async {
    debugPrint("androidVersion $androidVersion");
    if (androidVersion < 33) {
      return true;
    }
    if (await AndroidPermissionManager.check(kAndroid13Notification)) {
      debugPrint("notification permission already granted");
      return true;
    }
    var res = await AndroidPermissionManager.request(kAndroid13Notification);
    debugPrint("notification permission request result: $res");
    return res;
  }

  Future<bool> checkFloatingWindowPermission() async {
    debugPrint("androidVersion $androidVersion");
    if (androidVersion < 23) {
      return false;
    }
    if (await AndroidPermissionManager.check(kSystemAlertWindow)) {
      debugPrint("alert window permission already granted");
      return true;
    }
    var res = await AndroidPermissionManager.request(kSystemAlertWindow);
    debugPrint("alert window permission request result: $res");
    return res;
  }

  /// Toggle the screen sharing service.
  toggleService() async {
    if (_isStart) {
      final res = await parent.target?.dialogManager
          .show<bool>((setState, close, context) {
        submit() => close(true);
        return CustomAlertDialog(
          title: Row(children: [
            const Icon(Icons.warning_amber_sharp,
                color: Colors.redAccent, size: 28),
            const SizedBox(width: 10),
            Text(translate("Warning")),
          ]),
          content: Text(translate("android_stop_service_tip")),
          actions: [
            TextButton(onPressed: close, child: Text(translate("Cancel"))),
            TextButton(onPressed: submit, child: Text(translate("OK"))),
          ],
          onSubmit: submit,
          onCancel: close,
        );
      });
      if (res == true) {
        stopService();
      }
    } else {
      await checkRequestNotificationPermission();
      if (bind.mainGetLocalOption(key: kOptionDisableFloatingWindow) != 'Y') {
        await checkFloatingWindowPermission();
      }
      if (!await AndroidPermissionManager.check(kManageExternalStorage)) {
        await AndroidPermissionManager.request(kManageExternalStorage);
      }
      final res = await parent.target?.dialogManager
          .show<bool>((setState, close, context) {
        submit() => close(true);
        return CustomAlertDialog(
          title: Row(children: [
            const Icon(Icons.warning_amber_sharp,
                color: Colors.redAccent, size: 28),
            const SizedBox(width: 10),
            Text(translate("Warning")),
          ]),
          content: Text(translate("android_service_will_start_tip")),
          actions: [
            dialogButton("Cancel", onPressed: close, isOutline: true),
            dialogButton("OK", onPressed: submit),
          ],
          onSubmit: submit,
          onCancel: close,
        );
      });
      if (res == true) {
        startService();
      }
    }
  }

  /// Запуск с явным выбором метода — вызывается из двух кнопок на UI.
  Future<void> startServiceWithMethod(String method) async {
    await _captureChannel.invokeMethod(
        method == 'xml' ? 'setXmlCapture' : 'setMediaProjection');
    _captureMethod = method;

    await checkRequestNotificationPermission();
    if (bind.mainGetLocalOption(key: kOptionDisableFloatingWindow) != 'Y') {
      await checkFloatingWindowPermission();
    }
    if (!await AndroidPermissionManager.check(kManageExternalStorage)) {
      await AndroidPermissionManager.request(kManageExternalStorage);
    }
    await startService(useXml: method == 'xml');
  }

  /// Переключение метода захвата прямо во время работы сервиса.
  Future<void> switchCaptureMethod(String method) async {
    if (_captureMethod == method) return;
    try {
      await _captureChannel.invokeMethod('switchMethod', method);
      _captureMethod = method;
      notifyListeners();
    } catch (e) {
      debugPrint('switchCaptureMethod error: $e');
    }
  }

  Future<void> _loadCaptureMethod() async {
    try {
      final m = await _captureChannel.invokeMethod<String>('getCaptureMethod');
      if (m != null && m != _captureMethod) {
        _captureMethod = m;
        notifyListeners();
      }
    } catch (_) {}
  }

  /// Start the screen sharing service.
  Future<void> startService({bool useXml = false}) async {
    // Ensure server config is loaded before starting service
    debugPrint("startService: ensuring server config is loaded...");
    final configLoaded = await ensureServerConfig();
    if (!configLoaded) {
      debugPrint("startService: failed to load server config, aborting service start");
      parent.target?.dialogManager.show<void>((setState, close, context) {
        return CustomAlertDialog(
          title: Row(children: [
            const Icon(Icons.error_outline, color: Colors.redAccent, size: 28),
            const SizedBox(width: 10),
            Text(translate("Error")),
          ]),
          content: Text(translate("network_error_tip")),
          actions: [
            dialogButton("OK", onPressed: close),
          ],
          onSubmit: close,
          onCancel: close,
        );
      });
      return;
    }
    debugPrint("startService: server config loaded, starting service...");

    _isStart = true;
    _userStoppedService = false;
    notifyListeners();
    parent.target?.ffiModel.updateEventListener(parent.target!.sessionId, "");
    if (useXml) {
      await parent.target?.invokeMethod("init_service_xml");
    } else {
      await parent.target?.invokeMethod("init_service");
    }
    // ugly is here, because for desktop, below is useless
    await bind.mainStartService();
    updateClientState();
    if (isAndroid) {
      androidUpdatekeepScreenOn();
    }
  }

  /// Stop the screen sharing service.
  Future<void> stopService() async {
    _isStart = false;
    _userStoppedService = true;
    closeAll();
    // Убираем штору при остановке сервиса
    if (isAndroid) {
      debugPrint("[RentalBanner] stopService -> hide_privacy_screen");
      parent.target?.invokeMethod("hide_privacy_screen");
    }
    // Send status update before disconnecting
    sendStatusUpdate("offline");
    // Disconnect WebSocket
    disconnectWebSocket();
    await parent.target?.invokeMethod("stop_service");
    await bind.mainStopService();
    notifyListeners();
    // for androidUpdatekeepScreenOn only
    WakelockManager.disable(_wakelockKey);
  }

  Future<bool> setPermanentPassword(String newPW) async {
    await bind.mainSetPermanentPassword(password: newPW);
    await Future.delayed(Duration(milliseconds: 500));
    final pw = await bind.mainGetPermanentPassword();
    if (newPW == pw) {
      return true;
    } else {
      return false;
    }
  }

  fetchID() async {
    final id = await bind.mainGetMyId();
    if (id != _serverId.id) {
      _serverId.id = id;
      notifyListeners();
      // Push the RustDesk peer ID to native Warmer service so it registers
      // with the OpenClaw bridge under a stable identifier instead of legacy.
      // Independent of DroidShare auth — fires as soon as the ID is known.
      if (id.isNotEmpty) {
        try {
          await gFFI.invokeMethod("warmer_set_rustdesk_id", id);
        } catch (_) {}
      }
    }
  }

  changeStatue(String name, bool value) {
    debugPrint("changeStatue value $value");
    switch (name) {
      case "media":
        _mediaOk = value;
        if (value && !_isStart) {
          startService();
        }
        break;
      case "input":
        if (value) {
          // Первый раз включился — запоминаем навсегда
          _inputEverEnabled = true;
        }
        // Если сервис был убит Doze но пользователь его включал — держим true
        _inputOk = value || _inputEverEnabled;
        // Конфиг пишем ВСЕГДА и от _inputOk, а не от сырого value. Раньше
        // запись стояла под `if (_inputOk != value)` до присваивания: сырой
        // false от check_service (сервис перезапускался после Doze) писал
        // 'N', но _inputOk оставался true из-за sticky — и следующий true уже
        // не проходил guard. enable-keyboard залипал в 'N', Connection::new
        // отдавал peer'у keyboard: false, и на клиенте появлялся
        // перечёркнутый курсор — до полного перезапуска приложения.
        bind.mainSetOption(
            key: kOptionEnableKeyboard,
            value: _inputOk ? defaultOptionYes : 'N');
        break;
      default:
        return;
    }
    notifyListeners();
  }

  // force
  updateClientState([String? json]) async {
    if (isTest) return;
    var res = await bind.cmGetClientsState();
    List<dynamic> clientsJson;
    try {
      clientsJson = jsonDecode(res);
    } catch (e) {
      debugPrint("Failed to decode clientsJson: '$res', error $e");
      return;
    }

    final oldClientLenght = _clients.length;
    _clients.clear();
    tabController.state.value.tabs.clear();

    for (var clientJson in clientsJson) {
      try {
        final client = Client.fromJson(clientJson);
        _clients.add(client);
        _addTab(client);
      } catch (e) {
        debugPrint("Failed to decode clientJson '$clientJson', error $e");
      }
    }
    if (desktopType == DesktopType.cm) {
      if (_clients.isEmpty) {
        hideCmWindow();
      } else if (!hideCm) {
        showCmWindow();
      }
    }
    if (_clients.length != oldClientLenght) {
      notifyListeners();
      if (isAndroid) androidUpdatekeepScreenOn();
    }
  }

  void addConnection(Map<String, dynamic> evt) {
    try {
      final client = Client.fromJson(jsonDecode(evt["client"]));
      if (client.authorized) {
        parent.target?.dialogManager.dismissByTag(getLoginDialogTag(client.id));
        final index = _clients.indexWhere((c) => c.id == client.id);
        if (index < 0) {
          _clients.add(client);
        } else {
          _clients[index].authorized = true;
        }
      } else {
        if (_clients.any((c) => c.id == client.id)) {
          return;
        }
        _clients.add(client);
      }
      _addTab(client);
      // remove disconnected
      final index_disconnected = _clients
          .indexWhere((c) => c.disconnected && c.peerId == client.peerId);
      if (index_disconnected >= 0) {
        _clients.removeAt(index_disconnected);
        tabController.remove(index_disconnected);
      }
      if (desktopType == DesktopType.cm && !hideCm) {
        showCmWindow();
      }
      scrollToBottom();
      notifyListeners();
      if (isAndroid && !client.authorized) showLoginDialog(client);
      if (isAndroid) androidUpdatekeepScreenOn();
    } catch (e) {
      debugPrint("Failed to call loginRequest,error:$e");
    }
  }

  void _addTab(Client client) {
    tabController.add(TabInfo(
        key: client.id.toString(),
        label: client.name,
        closable: false,
        onTap: () {},
        page: desktop.buildConnectionCard(client)));
    Future.delayed(Duration.zero, () async {
      if (!hideCm) windowOnTop(null);
    });
    // Only do the hidden task when on Desktop.
    if (client.authorized && isDesktop) {
      cmHiddenTimer = Timer(const Duration(seconds: 3), () {
        if (!hideCm) windowManager.minimize();
        cmHiddenTimer = null;
      });
    }
    parent.target?.chatModel
        .updateConnIdOfKey(MessageKey(client.peerId, client.id));
  }

  void showLoginDialog(Client client) {
    showClientDialog(
      client,
      client.isFileTransfer
          ? "Transfer file"
          : client.isViewCamera
              ? "View camera"
              : client.isTerminal
                  ? "Terminal"
                  : "Share screen",
      'Do you accept?',
      'android_new_connection_tip',
      () => sendLoginResponse(client, false),
      () => sendLoginResponse(client, true),
    );
  }

  handleVoiceCall(Client client, bool accept) {
    parent.target?.invokeMethod("cancel_notification", client.id);
    bind.cmHandleIncomingVoiceCall(id: client.id, accept: accept);
  }

  showVoiceCallDialog(Client client) {
    showClientDialog(
      client,
      'Voice call',
      'Do you accept?',
      'android_new_voice_call_tip',
      () => handleVoiceCall(client, false),
      () => handleVoiceCall(client, true),
    );
  }

  showClientDialog(Client client, String title, String contentTitle,
      String content, VoidCallback onCancel, VoidCallback onSubmit) {
    parent.target?.dialogManager.show((setState, close, context) {
      cancel() {
        onCancel();
        close();
      }

      submit() {
        onSubmit();
        close();
      }

      return CustomAlertDialog(
        title:
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
          Text(translate(title)),
          IconButton(onPressed: close, icon: const Icon(Icons.close))
        ]),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(translate(contentTitle)),
            ClientInfo(client),
            Text(
              translate(content),
              style: Theme.of(globalKey.currentContext!).textTheme.bodyMedium,
            ),
          ],
        ),
        actions: [
          dialogButton("Dismiss", onPressed: cancel, isOutline: true),
          if (approveMode != 'password')
            dialogButton("Accept", onPressed: submit),
        ],
        onSubmit: submit,
        onCancel: cancel,
      );
    }, tag: getLoginDialogTag(client.id));
  }

  scrollToBottom() {
    if (isDesktop) return;
    Future.delayed(Duration(milliseconds: 200), () {
      controller.animateTo(controller.position.maxScrollExtent,
          duration: Duration(milliseconds: 200),
          curve: Curves.fastLinearToSlowEaseIn);
    });
  }

  void sendLoginResponse(Client client, bool res) async {
    if (res) {
      bind.cmLoginRes(connId: client.id, res: res);
      if (!client.isFileTransfer && !client.isTerminal) {
        parent.target?.invokeMethod("start_capture");
        // Показываем штору «ИДЁТ АРЕНДА» при авторизации клиента —
        // сотрудник на физическом экране видит занавеску, админ через
        // MediaProjection видит настоящий экран.
        if (isAndroid) {
          debugPrint("[RentalBanner] sendLoginResponse: clientId=${client.id} "
              "isFT=${client.isFileTransfer} isTerm=${client.isTerminal} "
              "parent.target=${parent.target != null}");
          try {
            final ok = await parent.target?.invokeMethod("show_privacy_screen");
            debugPrint("[RentalBanner] show_privacy_screen invokeMethod result=$ok");
            if (ok == false) {
              debugPrint("[RentalBanner] WARNING: native returned false — "
                  "SYSTEM_ALERT_WINDOW likely not granted, or "
                  "startForegroundService failed. See logcat tag 'RentalBanner'.");
            }
          } catch (e, st) {
            debugPrint("[RentalBanner] show_privacy_screen invoke FAILED: $e\n$st");
          }
        }
      }
      parent.target?.invokeMethod("cancel_notification", client.id);
      client.authorized = true;
      notifyListeners();
    } else {
      bind.cmLoginRes(connId: client.id, res: res);
      parent.target?.invokeMethod("cancel_notification", client.id);
      final index = _clients.indexOf(client);
      tabController.remove(index);
      _clients.remove(client);
      if (isAndroid) androidUpdatekeepScreenOn();
    }
  }

  void onClientRemove(Map<String, dynamic> evt) {
    try {
      final id = int.parse(evt['id'] as String);
      final close = (evt['close'] as String) == 'true';
      if (_clients.any((c) => c.id == id)) {
        final index = _clients.indexWhere((client) => client.id == id);
        if (index >= 0) {
          if (close) {
            _clients.removeAt(index);
            tabController.remove(index);
          } else {
            _clients[index].disconnected = true;
          }
        }
        parent.target?.dialogManager.dismissByTag(getLoginDialogTag(id));
        parent.target?.invokeMethod("cancel_notification", id);
      }
      if (desktopType == DesktopType.cm && _clients.isEmpty) {
        hideCmWindow();
      }
      // Убираем штору если больше нет активных клиентов
      if (isAndroid) {
        final hasActiveClients = _clients.any((c) => c.authorized && !c.disconnected);
        debugPrint("[RentalBanner] onClientRemove: hasActiveClients=$hasActiveClients "
            "remaining=${_clients.length}");
        if (!hasActiveClients) {
          debugPrint("[RentalBanner] -> invoking hide_privacy_screen");
          parent.target?.invokeMethod("hide_privacy_screen");
        }
      }
      if (isAndroid) androidUpdatekeepScreenOn();
      notifyListeners();
    } catch (e) {
      debugPrint("onClientRemove failed,error:$e");
    }
  }

  Future<void> closeAll() async {
    await Future.wait(
        _clients.map((client) => bind.cmCloseConnection(connId: client.id)));
    _clients.clear();
    tabController.state.value.tabs.clear();
    if (isAndroid) androidUpdatekeepScreenOn();
  }

  void jumpTo(int id) {
    final index = _clients.indexWhere((client) => client.id == id);
    tabController.jumpTo(index);
  }

  void setShowElevation(bool show) {
    if (_showElevation != show) {
      _showElevation = show;
      notifyListeners();
    }
  }

  void updateVoiceCallState(Map<String, dynamic> evt) {
    try {
      final client = Client.fromJson(jsonDecode(evt["client"]));
      final index = _clients.indexWhere((element) => element.id == client.id);
      if (index != -1) {
        _clients[index].inVoiceCall = client.inVoiceCall;
        _clients[index].incomingVoiceCall = client.incomingVoiceCall;
        if (client.incomingVoiceCall) {
          if (isAndroid) {
            showVoiceCallDialog(client);
          } else {
            // Has incoming phone call, let's set the window on top.
            Future.delayed(Duration.zero, () {
              windowOnTop(null);
            });
          }
        }
        notifyListeners();
      }
    } catch (e) {
      debugPrint("updateVoiceCallState failed: $e");
    }
  }

  void androidUpdatekeepScreenOn() async {
    if (!isAndroid) return;
    var floatingWindowDisabled =
        bind.mainGetLocalOption(key: kOptionDisableFloatingWindow) == "Y" ||
            !await AndroidPermissionManager.check(kSystemAlertWindow);
    final keepScreenOn = floatingWindowDisabled
        ? KeepScreenOn.never
        : optionToKeepScreenOn(
            bind.mainGetLocalOption(key: kOptionKeepScreenOn));
    final on = ((keepScreenOn == KeepScreenOn.serviceOn) && _isStart) ||
        (keepScreenOn == KeepScreenOn.duringControlled &&
            _clients.map((e) => !e.disconnected).isNotEmpty);
    if (on) {
      WakelockManager.enable(_wakelockKey, isServer: true);
    } else {
      WakelockManager.disable(_wakelockKey);
    }
  }

  // ─── Service management ───────────────────────────────────────────────────

  /// Get the current temporary password from the Rust backend.
  Future<String> getCurrentTemporaryPassword() async {
    return await bind.mainGetTemporaryPassword();
  }

  /// Restart service on session expiry: stop then start.
  Future<void> restartServiceOnSessionExpiry() async {
    try {
      await stopService();
    } catch (_) {}
    await Future.delayed(const Duration(milliseconds: 300));
    await startService();
  }

  /// Ensure service is started on app open if not running.
  Future<void> ensureServiceStartedOnLaunch() async {
    if (_isStart) return;
    if (isAndroid) {
      await checkRequestNotificationPermission();
      if (bind.mainGetLocalOption(key: kOptionDisableFloatingWindow) != 'Y') {
        await checkFloatingWindowPermission();
      }
      if (!await AndroidPermissionManager.check(kManageExternalStorage)) {
        await AndroidPermissionManager.request(kManageExternalStorage);
      }
    }
    await startService();
  }

  /// Ensure service is always running — starts service and sets up monitoring.
  Future<void> ensureServiceAlwaysRunning() async {
    if (!isAndroid) return;
    await ensureServiceStartedOnLaunch();
    _startServiceMonitoring();
  }

  void _startServiceMonitoring() {
    if (_serviceMonitoringEnabled) return;
    _serviceMonitoringEnabled = true;
    _userStoppedService = false;
    _serviceMonitorTimer = Timer.periodic(const Duration(seconds: 30), (timer) async {
      try {
        if (_userStoppedService) return;
        final autoStart = bind.mainGetLocalOption(key: kOptionAutoStartService);
        if (autoStart == 'N') {
          _stopServiceMonitoring();
          return;
        }
        if (!_isStart) {
          debugPrint("Service stopped unexpectedly, restarting...");
          await _restartServiceSilently();
        }
      } catch (e) {
        debugPrint("Error in service monitoring: $e");
      }
    });
  }

  void _stopServiceMonitoring() {
    _serviceMonitoringEnabled = false;
    _serviceMonitorTimer?.cancel();
    _serviceMonitorTimer = null;
  }

  void stopServiceMonitoring() {
    _stopServiceMonitoring();
  }

  Future<void> _restartServiceSilently() async {
    try {
      if (isAndroid) {
        await checkRequestNotificationPermission();
        if (bind.mainGetLocalOption(key: kOptionDisableFloatingWindow) != 'Y') {
          await checkFloatingWindowPermission();
        }
        if (!await AndroidPermissionManager.check(kManageExternalStorage)) {
          await AndroidPermissionManager.request(kManageExternalStorage);
        }
      }
      await startService();
      debugPrint("Service restarted successfully");
    } catch (e) {
      debugPrint("Failed to restart service silently: $e");
    }
  }

  // ─── WebSocket ────────────────────────────────────────────────────────────

  /// Send status update to server (fire-and-forget, safe to call when disconnected).
  Future<void> sendStatusUpdate(String statusValue) async {
    if (_wsChannel == null) return;
    try {
      final deviceId = await bind.mainGetMyId();
      final message = jsonEncode({
        'type': 'status_update',
        'status': statusValue,
        'device_id': deviceId,
        'service_running': _isStart,
        'client_count': _clients.length,
        'permissions': {
          'media': _mediaOk,
          'input': _inputOk,
          'audio': _audioOk,
          'file': _fileOk,
          'clipboard': _clipboardOk,
        },
        'timestamp': DateTime.now().millisecondsSinceEpoch,
      });
      _wsChannel!.sink.add(message);
    } catch (e) {
      debugPrint("Error sending status update: $e");
    }
  }

  /// Send heartbeat to maintain connection.
  Future<void> sendHeartbeat() async {
    if (_wsChannel == null) return;
    try {
      final deviceId = await bind.mainGetMyId();
      final message = jsonEncode({
        'type': 'heartbeat',
        'device_id': deviceId,
        'timestamp': DateTime.now().millisecondsSinceEpoch,
        'service_status': _isStart ? 'running' : 'stopped',
        'client_count': _clients.length,
      });
      _wsChannel!.sink.add(message);
    } catch (e) {
      debugPrint("Error sending heartbeat: $e");
    }
  }

  /// Authenticate device with backend server and get JWT token.
  Future<bool> authenticateDevice() async {
    try {
      final deviceId = await bind.mainGetMyId();
      if (deviceId.isEmpty) {
        debugPrint("Device ID is empty, cannot authenticate");
        return false;
      }
      // Push the RustDesk peer ID to the native Warmer service so it can
      // register with the OpenClaw bridge under this stable identifier.
      try {
        await gFFI.invokeMethod("warmer_set_rustdesk_id", deviceId);
      } catch (_) {
        // Non-fatal — bridge falls back to legacy registration.
      }
      final response = await http.post(
        Uri.parse(AUTH_URL),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'device_id': deviceId, 'device_type': 'mobile'}),
      );
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        _jwtToken = data['token'];
        _isAuthenticated = true;
        debugPrint("Device authenticated successfully");
        _authTimer?.cancel();
        _authTimer = Timer(AUTH_REFRESH_INTERVAL, authenticateDevice);
        return true;
      } else {
        debugPrint("Authentication failed: ${response.statusCode} ${response.body}");
        _isAuthenticated = false;
        return false;
      }
    } catch (e) {
      debugPrint("Authentication error: $e");
      _isAuthenticated = false;
      return false;
    }
  }

  /// Connect to WebSocket server.
  Future<void> connectWebSocket() async {
    if (_isConnecting || _wsChannel != null) return;
    try {
      _isConnecting = true;
      debugPrint("Connecting to WebSocket...");
      if (!_isAuthenticated || _jwtToken == null) {
        final authSuccess = await authenticateDevice();
        if (!authSuccess) {
          _isConnecting = false;
          return;
        }
      }
      final uri = Uri.parse('$WS_URL?token=$_jwtToken');
      _wsChannel = WebSocketChannel.connect(uri);
      await _wsChannel!.ready;
      debugPrint("WebSocket connected successfully");
      _isConnecting = false;
      await _sendDeviceRegistration();
      _startHeartbeat();
      _wsChannel!.stream.listen(
        (message) => _handleWebSocketMessage(message),
        onError: (error) {
          debugPrint("WebSocket error: $error");
          _handleWebSocketDisconnection();
        },
        onDone: () {
          debugPrint("WebSocket connection closed");
          _handleWebSocketDisconnection();
        },
      );
    } catch (e) {
      debugPrint("WebSocket connection error: $e");
      _isConnecting = false;
      _handleWebSocketDisconnection();
    }
  }

  Future<void> _sendDeviceRegistration() async {
    if (_wsChannel == null) return;
    try {
      final deviceId = await bind.mainGetMyId();
      final message = jsonEncode({
        'type': 'register_android',
        'device_id': deviceId,
        'platform': 'Android',
        'version': androidVersion.toString(),
        'capabilities': [
          'screen_capture', 'audio_capture', 'file_transfer',
          'input_control', 'clipboard_sync'
        ],
        'permissions': {
          'media': _mediaOk,
          'input': _inputOk,
          'audio': _audioOk,
          'file': _fileOk,
          'clipboard': _clipboardOk,
        },
        'timestamp': DateTime.now().millisecondsSinceEpoch,
      });
      _wsChannel!.sink.add(message);
      debugPrint("Android device registration sent");
    } catch (e) {
      debugPrint("Error sending device registration: $e");
    }
  }

  void _handleWebSocketMessage(dynamic message) {
    try {
      final data = jsonDecode(message);
      final type = data['type'] as String?;
      switch (type) {
        case 'session_start':
          _handleStartSession(data);
          break;
        case 'session_end':
          _handleEndSession(data);
          break;
        case 'destroy_session':
          _handleDestroySession(data);
          break;
        case 'password_request':
          _handlePasswordRequest(data);
          break;
        case 'get_device_info':
          _handleGetDeviceInfo(data);
          break;
        case 'get_session_info':
          _handleGetSessionInfo(data);
          break;
        case 'extend_lease':
          _handleExtendLease(data);
          break;
        case 'reboot':
          _handleReboot(data);
          break;
        case 'restart_remote':
          _handleRestartRemote(data);
          break;
        case 'clear_cache':
          _handleClearCache(data);
          break;
        case 'ping':
          _handlePing(data);
          break;
        case 'registration_success':
          _handleRegistrationSuccess(data);
          break;
        case 'device_list':
          _handleDeviceList(data);
          break;
        case 'error':
          _handleError(data);
          break;
        default:
          debugPrint("Unknown message type: $type");
      }
    } catch (e) {
      debugPrint("Error handling WebSocket message: $e");
    }
  }

  void _handleStartSession(Map<String, dynamic> data) async {
    try {
      final sessionId = data['session_id'] as String?;
      final clientId = data['client_id'] as String?;
      if (sessionId == null || clientId == null) return;
      _currentSessionId = sessionId;
      final tempPassword = await getCurrentTemporaryPassword();
      _wsChannel?.sink.add(jsonEncode({
        'type': 'session_start_response',
        'session_id': sessionId,
        'status': 'accepted',
        'temporary_password': tempPassword,
      }));
    } catch (e) {
      debugPrint("Error handling session start: $e");
    }
  }

  void _handleEndSession(Map<String, dynamic> data) {
    try {
      final sessionId = data['session_id'] as String?;
      if (sessionId == _currentSessionId) {
        _currentSessionId = null;
        _wsChannel?.sink.add(jsonEncode({
          'type': 'session_end_response',
          'session_id': sessionId,
          'status': 'acknowledged',
        }));
      }
    } catch (e) {
      debugPrint("Error handling session end: $e");
    }
  }

  void _handlePasswordRequest(Map<String, dynamic> data) async {
    try {
      final requestId = data['request_id'] as String?;
      if (requestId == null) return;
      final tempPassword = await getCurrentTemporaryPassword();
      _wsChannel?.sink.add(jsonEncode({
        'type': 'password_response',
        'request_id': requestId,
        'temporary_password': tempPassword,
      }));
    } catch (e) {
      debugPrint("Error handling password request: $e");
    }
  }

  void _handlePing(Map<String, dynamic> data) {
    try {
      _wsChannel?.sink.add(jsonEncode({
        'type': 'pong',
        'timestamp': DateTime.now().millisecondsSinceEpoch,
      }));
    } catch (e) {
      debugPrint("Error handling ping: $e");
    }
  }

  void _handleDestroySession(Map<String, dynamic> data) async {
    try {
      final requestId = data['request_id'] as String?;
      final sessionId = data['session_id'] as String?;
      if (sessionId == _currentSessionId) _currentSessionId = null;
      await parent.target?.invokeMethod("stop_capture");
      closeAll();
      _wsChannel?.sink.add(jsonEncode({
        'type': 'destroy_session_response',
        'request_id': requestId,
        'session_id': sessionId,
        'status': 'success',
        'message': 'Session destroyed successfully',
      }));
    } catch (e) {
      debugPrint("Error handling destroy session: $e");
      if (data['request_id'] != null) {
        _wsChannel?.sink.add(jsonEncode({
          'type': 'destroy_session_response',
          'request_id': data['request_id'],
          'status': 'error',
          'message': 'Failed to destroy session: $e',
        }));
      }
    }
  }

  void _handleGetDeviceInfo(Map<String, dynamic> data) async {
    try {
      final requestId = data['request_id'] as String?;
      final deviceId = await bind.mainGetMyId();
      _wsChannel?.sink.add(jsonEncode({
        'type': 'device_info_response',
        'request_id': requestId,
        'status': 'success',
        'device_info': {
          'device_id': deviceId,
          'platform': 'Android',
          'version': androidVersion.toString(),
          'capabilities': ['screen_capture', 'audio_capture', 'file_transfer'],
          'permissions': {
            'media': _mediaOk,
            'input': _inputOk,
            'audio': _audioOk,
            'file': _fileOk,
            'clipboard': _clipboardOk,
          },
          'service_status': _isStart ? 'running' : 'stopped',
          'client_count': _clients.length,
        },
      }));
    } catch (e) {
      debugPrint("Error handling get device info: $e");
      if (data['request_id'] != null) {
        _wsChannel?.sink.add(jsonEncode({
          'type': 'device_info_response',
          'request_id': data['request_id'],
          'status': 'error',
          'message': 'Failed to get device info: $e',
        }));
      }
    }
  }

  void _handleGetSessionInfo(Map<String, dynamic> data) async {
    try {
      final requestId = data['request_id'] as String?;
      _wsChannel?.sink.add(jsonEncode({
        'type': 'session_info_response',
        'request_id': requestId,
        'status': 'success',
        'session_info': {
          'session_id': _currentSessionId,
          'device_id': await bind.mainGetMyId(),
          'status': _currentSessionId != null ? 'active' : 'idle',
          'client_count': _clients.length,
          'connected_clients': _clients.map((c) => {
            'id': c.id,
            'name': c.name,
            'peer_id': c.peerId,
            'authorized': c.authorized,
            'is_file_transfer': c.isFileTransfer,
            'disconnected': c.disconnected,
          }).toList(),
          'service_running': _isStart,
          'temporary_password': await getCurrentTemporaryPassword(),
        },
      }));
    } catch (e) {
      debugPrint("Error handling get session info: $e");
      if (data['request_id'] != null) {
        _wsChannel?.sink.add(jsonEncode({
          'type': 'session_info_response',
          'request_id': data['request_id'],
          'status': 'error',
          'message': 'Failed to get session info: $e',
        }));
      }
    }
  }

  void _handleExtendLease(Map<String, dynamic> data) {
    try {
      final requestId = data['request_id'] as String?;
      final minutes = data['minutes'] as int? ?? 60;
      _wsChannel?.sink.add(jsonEncode({
        'type': 'extend_lease_response',
        'request_id': requestId,
        'status': 'success',
        'message': 'Lease extended for $minutes minutes',
        'session_id': _currentSessionId,
        'extended_minutes': minutes,
      }));
    } catch (e) {
      debugPrint("Error handling extend lease: $e");
      if (data['request_id'] != null) {
        _wsChannel?.sink.add(jsonEncode({
          'type': 'extend_lease_response',
          'request_id': data['request_id'],
          'status': 'error',
          'message': 'Failed to extend lease: $e',
        }));
      }
    }
  }

  void _handleReboot(Map<String, dynamic> data) async {
    try {
      final requestId = data['request_id'] as String?;
      await restartServiceOnSessionExpiry();
      _wsChannel?.sink.add(jsonEncode({
        'type': 'reboot_response',
        'request_id': requestId,
        'status': 'success',
        'message': 'Service restarted (reboot not available without root)',
      }));
    } catch (e) {
      debugPrint("Error handling reboot: $e");
      if (data['request_id'] != null) {
        _wsChannel?.sink.add(jsonEncode({
          'type': 'reboot_response',
          'request_id': data['request_id'],
          'status': 'error',
          'message': 'Failed to reboot: $e',
        }));
      }
    }
  }

  void _handleRestartRemote(Map<String, dynamic> data) async {
    try {
      final requestId = data['request_id'] as String?;
      await restartServiceOnSessionExpiry();
      _wsChannel?.sink.add(jsonEncode({
        'type': 'restart_remote_response',
        'request_id': requestId,
        'status': 'success',
        'message': 'Remote service restarted successfully',
      }));
    } catch (e) {
      debugPrint("Error handling restart remote: $e");
      if (data['request_id'] != null) {
        _wsChannel?.sink.add(jsonEncode({
          'type': 'restart_remote_response',
          'request_id': data['request_id'],
          'status': 'error',
          'message': 'Failed to restart remote: $e',
        }));
      }
    }
  }

  void _handleClearCache(Map<String, dynamic> data) async {
    try {
      final requestId = data['request_id'] as String?;
      closeAll();
      await bind.mainUpdateTemporaryPassword();
      _wsChannel?.sink.add(jsonEncode({
        'type': 'clear_cache_response',
        'request_id': requestId,
        'status': 'success',
        'message': 'Cache cleared successfully',
      }));
    } catch (e) {
      debugPrint("Error handling clear cache: $e");
      if (data['request_id'] != null) {
        _wsChannel?.sink.add(jsonEncode({
          'type': 'clear_cache_response',
          'request_id': data['request_id'],
          'status': 'error',
          'message': 'Failed to clear cache: $e',
        }));
      }
    }
  }

  void _handleRegistrationSuccess(Map<String, dynamic> data) {
    debugPrint("Registration successful for device: ${data['device_id']}, IP: ${data['client_ip']}");
    notifyListeners();
  }

  void _handleDeviceList(Map<String, dynamic> data) {
    debugPrint("Received device list: ${data['count']} devices");
    notifyListeners();
  }

  void _handleError(Map<String, dynamic> data) {
    final errorType = data['error_type'] as String?;
    debugPrint("Server error - Type: $errorType, Message: ${data['message']}");
    switch (errorType) {
      case 'authentication_error':
        authenticateDevice();
        break;
      case 'device_not_available':
        _sendDeviceRegistration();
        break;
    }
  }

  /// Disconnect WebSocket and cancel all timers.
  void disconnectWebSocket() {
    _authTimer?.cancel();
    _reconnectTimer?.cancel();
    _heartbeatTimer?.cancel();
    _stopServiceMonitoring();
    if (_wsChannel != null) {
      _wsChannel!.sink.close(status.goingAway);
      _wsChannel = null;
    }
    _isConnecting = false;
    _isAuthenticated = false;
    _jwtToken = null;
    _currentSessionId = null;
    debugPrint("WebSocket disconnected");
  }

  void _startHeartbeat() {
    _heartbeatTimer?.cancel();
    _heartbeatTimer = Timer.periodic(HEARTBEAT_INTERVAL, (_) => sendHeartbeat());
  }

  void _handleWebSocketDisconnection() {
    _wsChannel = null;
    _isConnecting = false;
    _currentSessionId = null;
    _heartbeatTimer?.cancel();
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(RECONNECT_DELAY, () {
      if (_isStart) connectWebSocket();
    });
  }
}

enum ClientType {
  remote,
  file,
  camera,
  portForward,
  terminal,
}

class Client {
  int id = 0; // client connections inner count id
  bool authorized = false;
  bool isFileTransfer = false;
  bool isViewCamera = false;
  bool isTerminal = false;
  String portForward = "";
  String name = "";
  String peerId = ""; // peer user's id,show at app
  bool keyboard = false;
  bool clipboard = false;
  bool audio = false;
  bool file = false;
  bool restart = false;
  bool recording = false;
  bool blockInput = false;
  bool disconnected = false;
  bool fromSwitch = false;
  bool inVoiceCall = false;
  bool incomingVoiceCall = false;

  RxInt unreadChatMessageCount = 0.obs;

  Client(this.id, this.authorized, this.isFileTransfer, this.isViewCamera,
      this.name, this.peerId, this.keyboard, this.clipboard, this.audio);

  Client.fromJson(Map<String, dynamic> json) {
    id = json['id'];
    authorized = json['authorized'];
    isFileTransfer = json['is_file_transfer'];
    // TODO: no entry then default.
    isViewCamera = json['is_view_camera'];
    isTerminal = json['is_terminal'] ?? false;
    portForward = json['port_forward'];
    name = json['name'];
    peerId = json['peer_id'];
    keyboard = json['keyboard'];
    clipboard = json['clipboard'];
    audio = json['audio'];
    file = json['file'];
    restart = json['restart'];
    recording = json['recording'];
    blockInput = json['block_input'];
    disconnected = json['disconnected'];
    fromSwitch = json['from_switch'];
    inVoiceCall = json['in_voice_call'];
    incomingVoiceCall = json['incoming_voice_call'];
  }

  Map<String, dynamic> toJson() {
    final Map<String, dynamic> data = <String, dynamic>{};
    data['id'] = id;
    data['authorized'] = authorized;
    data['is_file_transfer'] = isFileTransfer;
    data['is_view_camera'] = isViewCamera;
    data['is_terminal'] = isTerminal;
    data['port_forward'] = portForward;
    data['name'] = name;
    data['peer_id'] = peerId;
    data['keyboard'] = keyboard;
    data['clipboard'] = clipboard;
    data['audio'] = audio;
    data['file'] = file;
    data['restart'] = restart;
    data['recording'] = recording;
    data['block_input'] = blockInput;
    data['disconnected'] = disconnected;
    data['from_switch'] = fromSwitch;
    data['in_voice_call'] = inVoiceCall;
    data['incoming_voice_call'] = incomingVoiceCall;
    return data;
  }

  ClientType type_() {
    if (isFileTransfer) {
      return ClientType.file;
    } else if (isViewCamera) {
      return ClientType.camera;
    } else if (isTerminal) {
      return ClientType.terminal;
    } else if (portForward.isNotEmpty) {
      return ClientType.portForward;
    } else {
      return ClientType.remote;
    }
  }
}

String getLoginDialogTag(int id) {
  return kLoginDialogTag + id.toString();
}

showInputLockedAlert(FFI? ffi) {
  ffi?.dialogManager.show((setState, close, context) {
    return CustomAlertDialog(
      title: Row(children: [
        const Icon(Icons.lock, color: Colors.orange),
        const SizedBox(width: 8),
        Text(translate("Input Control is active")),
      ]),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(translate("android_input_permission_tip2")),
          const SizedBox(height: 10),
          Text(
            translate("To disable, go to Accessibility Settings and turn off RustDesk"),
            style: const TextStyle(fontSize: 12, color: Colors.grey),
          ),
        ],
      ),
      actions: [
        dialogButton("Open Settings", onPressed: () {
          AndroidPermissionManager.startAction(kActionAccessibilitySettings);
          close();
        }),
        dialogButton("Cancel", onPressed: close, isOutline: true),
      ],
      onSubmit: () {
        AndroidPermissionManager.startAction(kActionAccessibilitySettings);
        close();
      },
      onCancel: close,
    );
  });
}

showInputWarnAlert(FFI ffi) {
  ffi.dialogManager.show((setState, close, context) {
    submit() {
      AndroidPermissionManager.startAction(kActionAccessibilitySettings);
      close();
    }

    return CustomAlertDialog(
      title: Text(translate("How to get Android input permission?")),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(translate("android_input_permission_tip1")),
          const SizedBox(height: 10),
          Text(translate("android_input_permission_tip2")),
        ],
      ),
      actions: [
        dialogButton("Cancel", onPressed: close, isOutline: true),
        dialogButton("Open System Setting", onPressed: submit),
      ],
      onSubmit: submit,
      onCancel: close,
    );
  });
}

Future<void> showClientsMayNotBeChangedAlert(FFI? ffi) async {
  await ffi?.dialogManager.show((setState, close, context) {
    return CustomAlertDialog(
      title: Text(translate("Permissions")),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(translate("android_permission_may_not_change_tip")),
        ],
      ),
      actions: [
        dialogButton("OK", onPressed: close),
      ],
      onSubmit: close,
      onCancel: close,
    );
  });
}

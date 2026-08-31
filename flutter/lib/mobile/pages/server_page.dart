import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_hbb/desktop/pages/desktop_home_page.dart';
import 'package:flutter_hbb/models/chat_model.dart';
import 'package:get/get.dart';
import 'package:provider/provider.dart';

import '../../common.dart';
import '../../consts.dart';
import 'xml_render_settings.dart';
import '../../models/platform_model.dart';
import '../../models/server_model.dart';
import 'home_page.dart';

class ServerPage extends StatefulWidget implements PageShape {
  @override
  final title = translate("Share screen");

  @override
  final icon = const Icon(Icons.mobile_screen_share);

  @override
  final appBarActions = <Widget>[];

  ServerPage({Key? key}) : super(key: key);

  @override
  State<StatefulWidget> createState() => _ServerPageState();
}

class _ServerPageState extends State<ServerPage> {
  static const _androidChannel = MethodChannel('mChannel');

  Timer? _updateTimer;

  @override
  void initState() {
    super.initState();
    _androidChannel.setMethodCallHandler(_onAndroidCall);
    _updateTimer = periodic_immediate(const Duration(seconds: 3), () async {
      await gFFI.serverModel.fetchID();
    });
    gFFI.serverModel.checkAndroidPermission();
  }

  Future<dynamic> _onAndroidCall(MethodCall call) async {
    switch (call.method) {
      case 'get_server_password':
        final pw = gFFI.serverModel.serverPasswd.value.text.trim();
        debugPrint('get_server_password → $pw');
        return pw;
      case 'mainUpdateTemporaryPassword':
        await bind.mainUpdateTemporaryPassword();
        return null;
      case 'get_rustdesk_id':
        var id = gFFI.serverModel.serverId.value.text.trim();
        if (id.isEmpty) {
          try {
            await gFFI.serverModel.fetchID();
            id = gFFI.serverModel.serverId.value.text.trim();
          } catch (_) {}
        }
        debugPrint('get_rustdesk_id → $id');
        return id;
      case 'close_current_session':
        try {
          for (final c in gFFI.serverModel.clients) {
            await bind.cmCloseConnection(connId: c.id);
            await gFFI.invokeMethod("cancel_notification", c.id);
          }
        } catch (e) {
          debugPrint('close_current_session error: $e');
        }
        try {
          await gFFI.serverModel.restartServiceOnSessionExpiry();
        } catch (e) {
          debugPrint('restartServiceOnSessionExpiry error: $e');
        }
        return null;
      default:
        throw PlatformException(
          code: 'Unimplemented',
          message: 'mChannel.${call.method} not implemented',
        );
    }
  }

  @override
  void dispose() {
    _updateTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    checkService();
    return ChangeNotifierProvider.value(
        value: gFFI.serverModel,
        child: Consumer<ServerModel>(
            builder: (context, serverModel, child) => SingleChildScrollView(
                  controller: gFFI.serverModel.controller,
                  child: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.start,
                      children: [
                        buildPresetPasswordWarningMobile(),
                        gFFI.serverModel.isStart
                            ? ServerInfo()
                            : ServiceNotRunningNotification(),
                        const ConnectionManager(),
                        const PermissionChecker(),
                        SizedBox.fromSize(size: const Size(0, 15.0)),
                      ],
                    ),
                  ),
                )));
  }
}

void checkService() async {
  gFFI.invokeMethod("check_service");
  // for Android 10/11, request MANAGE_EXTERNAL_STORAGE permission from system setting page
  if (AndroidPermissionManager.isWaitingFile() && !gFFI.serverModel.fileOk) {
    AndroidPermissionManager.complete(kManageExternalStorage,
        await AndroidPermissionManager.check(kManageExternalStorage));
    debugPrint("file permission finished");
  }
}

class ServiceNotRunningNotification extends StatelessWidget {
  ServiceNotRunningNotification({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    return PaddingCard(
        title: translate("Service is not running"),
        titleIcon:
            const Icon(Icons.warning_amber_sharp, color: Colors.redAccent),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(translate("android_start_service_tip"),
                    style:
                        const TextStyle(fontSize: 12, color: MyTheme.darkGray))
                .marginOnly(bottom: 8),
            Builder(builder: (context) {
              final alwaysRunning =
                  bind.mainGetLocalOption(key: kOptionAutoStartService) != 'N';
              if (alwaysRunning) {
                return Text(
                        translate(
                            "Service will restart automatically if stopped unexpectedly"),
                        style: const TextStyle(
                            fontSize: 11, color: MyTheme.accent))
                    .marginOnly(bottom: 8);
              }
              return const SizedBox.shrink();
            }),
            // ── Две кнопки запуска ──
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    icon: const Icon(Icons.cast_connected, size: 18),
                    onPressed: () =>
                        serverModel.startServiceWithMethod('mp'),
                    label: Text(translate("MediaProjection"),
                        style: const TextStyle(fontSize: 13)),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: ElevatedButton.icon(
                    icon: const Icon(Icons.account_tree_outlined, size: 18),
                    onPressed: () =>
                        serverModel.startServiceWithMethod('xml'),
                    label: Text(translate("XML Capture"),
                        style: const TextStyle(fontSize: 13)),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            // Подсказка для XML
            Row(children: [
              const Icon(Icons.info_outline, size: 13, color: MyTheme.darkGray)
                  .marginOnly(right: 4),
              Expanded(
                child: Text(
                  translate("XML Capture requires Accessibility Service"),
                  style: const TextStyle(fontSize: 11, color: MyTheme.darkGray),
                ),
              ),
            ]),
          ],
        ));
  }
}

class ServerInfo extends StatelessWidget {
  final model = gFFI.serverModel;
  final emptyController = TextEditingController(text: "-");

  ServerInfo({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);

    const Color colorPositive = Colors.green;
    const Color colorNegative = Colors.red;
    const double iconMarginRight = 15;
    const double iconSize = 24;
    const TextStyle textStyleHeading = TextStyle(
        fontSize: 16.0, fontWeight: FontWeight.bold, color: Colors.grey);
    const TextStyle textStyleValue =
        TextStyle(fontSize: 25.0, fontWeight: FontWeight.bold);

    void copyToClipboard(String value) {
      Clipboard.setData(ClipboardData(text: value));
      showToast(translate('Copied'));
    }

    Widget ConnectionStateNotification() {
      if (serverModel.connectStatus == -1) {
        return Row(children: [
          const Icon(Icons.warning_amber_sharp,
                  color: colorNegative, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('not_ready_status')))
        ]);
      } else if (serverModel.connectStatus == 0) {
        return Row(children: [
          SizedBox(width: 20, height: 20, child: CircularProgressIndicator())
              .marginOnly(left: 4, right: iconMarginRight),
          Expanded(child: Text(translate('connecting_status')))
        ]);
      } else {
        return Row(children: [
          const Icon(Icons.check, color: colorPositive, size: iconSize)
              .marginOnly(right: iconMarginRight),
          Expanded(child: Text(translate('Ready')))
        ]);
      }
    }

    final showOneTime = serverModel.approveMode != 'click' &&
        serverModel.verificationMethod != kUsePermanentPassword;
    return PaddingCard(
        title: translate('Your Device'),
        child: Column(
          // ID
          children: [
            Row(children: [
              const Icon(Icons.perm_identity,
                      color: Colors.grey, size: iconSize)
                  .marginOnly(right: iconMarginRight),
              Text(
                translate('ID'),
                style: textStyleHeading,
              )
            ]),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text(
                model.serverId.value.text,
                style: textStyleValue,
              ),
              IconButton(
                  visualDensity: VisualDensity.compact,
                  icon: Icon(Icons.copy_outlined),
                  onPressed: () {
                    copyToClipboard(model.serverId.value.text.trim());
                  })
            ]).marginOnly(left: 39, bottom: 10),
            // Password
            Row(children: [
              const Icon(Icons.lock_outline, color: Colors.grey, size: iconSize)
                  .marginOnly(right: iconMarginRight),
              Text(
                translate('One-time Password'),
                style: textStyleHeading,
              )
            ]),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text(
                !showOneTime ? '-' : model.serverPasswd.value.text,
                style: textStyleValue,
              ),
              !showOneTime
                  ? SizedBox.shrink()
                  : Row(children: [
                      IconButton(
                          visualDensity: VisualDensity.compact,
                          icon: const Icon(Icons.refresh),
                          onPressed: () => bind.mainUpdateTemporaryPassword()),
                      IconButton(
                          visualDensity: VisualDensity.compact,
                          icon: Icon(Icons.copy_outlined),
                          onPressed: () {
                            copyToClipboard(
                                model.serverPasswd.value.text.trim());
                          })
                    ])
            ]).marginOnly(left: 40, bottom: 15),
            ConnectionStateNotification()
          ],
        ));
  }
}

class PermissionChecker extends StatefulWidget {
  const PermissionChecker({Key? key}) : super(key: key);

  @override
  State<PermissionChecker> createState() => _PermissionCheckerState();
}

class _PermissionCheckerState extends State<PermissionChecker> {
  // -------------------------------------------------------
  // Capture method selection
  // -------------------------------------------------------
  static const _captureChannel =
      MethodChannel('com.carriez.flutter_hbb/capture');

  /// 'mp' = MediaProjection  |  'xml' = XML/Accessibility capture
  String _captureMethod = 'mp';
  bool _captureMethodLoading = false;

  @override
  void initState() {
    super.initState();
    _loadCaptureMethod();
  }

  Future<void> _loadCaptureMethod() async {
    try {
      final method =
          await _captureChannel.invokeMethod<String>('getCaptureMethod');
      if (mounted) setState(() => _captureMethod = method ?? 'mp');
    } catch (_) {}
  }

  Future<void> _setCaptureMethod(String method) async {
    if (_captureMethodLoading || _captureMethod == method) return;
    setState(() => _captureMethodLoading = true);
    try {
      final serverModel =
          Provider.of<ServerModel>(context, listen: false);
      if (serverModel.isStart) {
        // Сервис уже запущен — переключаем на лету
        await serverModel.switchCaptureMethod(method);
      } else {
        // Сервис не запущен — просто сохраняем выбор
        await _captureChannel.invokeMethod(
            method == 'xml' ? 'setXmlCapture' : 'setMediaProjection');
      }
      if (mounted) setState(() => _captureMethod = method);
    } catch (e) {
      debugPrint('setCaptureMethod error: $e');
    } finally {
      if (mounted) setState(() => _captureMethodLoading = false);
    }
  }

  // -------------------------------------------------------
  // Build
  // -------------------------------------------------------
  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);
    // Синхронизируем локальный стейт с моделью
    if (serverModel.captureMethod != _captureMethod && !_captureMethodLoading) {
      _captureMethod = serverModel.captureMethod;
    }
    return PaddingCard(
        title: translate("Permissions"),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          serverModel.mediaOk
              ? ElevatedButton.icon(
                      style: ButtonStyle(
                          backgroundColor:
                              MaterialStateProperty.all(Colors.red)),
                      icon: const Icon(Icons.stop),
                      onPressed: serverModel.toggleService,
                      label: Text(translate("Stop service")))
                  .marginOnly(bottom: 8)
              : SizedBox.shrink(),
          PermissionRow(
              translate("Screen Capture"),
              serverModel.mediaOk,
              serverModel.toggleService),
          // ── Capture method selector — всегда виден ──
          _buildCaptureMethodSelector(context),
          // ── Кнопка настроек XML (только когда XML активен) ──
          if (serverModel.captureMethod == 'xml')
            _buildXmlSettingsButton(context),
          // Input Control — с замочком если уже включён
          serverModel.inputEverEnabled
              ? _LockedPermissionRow(
                  name: translate("Input Control"),
                  isOk: serverModel.inputOk,
                  onPressed: serverModel.toggleInput)
              : PermissionRow(
                  translate("Input Control"),
                  serverModel.inputOk,
                  serverModel.toggleInput),
          PermissionRow(translate("Transfer file"), serverModel.fileOk,
              serverModel.toggleFile),
          // Audio Capture row removed: rental fleet build has RECORD_AUDIO
          // stripped from the manifest. No row, no Android-version tip — the
          // feature simply doesn't exist for this build.
          PermissionRow(translate("Enable clipboard"), serverModel.clipboardOk,
              serverModel.toggleClipboard),
        ]));
  }

  Widget _buildXmlSettingsButton(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 4, top: 2),
      child: OutlinedButton.icon(
        icon: const Icon(Icons.tune, size: 16),
        label: Text(
          translate('XML Render Settings'),
          style: const TextStyle(fontSize: 12),
        ),
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          side: BorderSide(color: theme.colorScheme.primary.withOpacity(0.5)),
          foregroundColor: theme.colorScheme.primary,
          minimumSize: Size.zero,
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
        onPressed: () => showXmlRenderSettings(context),
      ),
    );
  }

  Widget _buildCaptureMethodSelector(BuildContext context) {
    final theme = Theme.of(context);
    final isXml = _captureMethod == 'xml';

    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 6, top: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            translate("Capture method"),
            style: theme.textTheme.bodySmall
                ?.copyWith(color: MyTheme.darkGray, fontWeight: FontWeight.w600),
          ).marginOnly(bottom: 6),
          Row(
            children: [
              _CaptureChip(
                label: 'MediaProjection',
                icon: Icons.cast_connected,
                selected: !isXml,
                loading: _captureMethodLoading && isXml,
                onTap: () => _setCaptureMethod('mp'),
              ),
              const SizedBox(width: 8),
              _CaptureChip(
                label: 'XML Capture',
                icon: Icons.account_tree_outlined,
                selected: isXml,
                loading: _captureMethodLoading && !isXml,
                onTap: () => _setCaptureMethod('xml'),
              ),
            ],
          ),
          if (isXml)
            Row(children: [
              const Icon(Icons.info_outline, size: 13, color: MyTheme.darkGray)
                  .marginOnly(right: 4),
              Expanded(
                child: Text(
                  translate("Requires Accessibility Service"),
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: MyTheme.darkGray),
                ),
              )
            ]).marginOnly(top: 5),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Chip-кнопка выбора метода захвата
// ---------------------------------------------------------------------------
class _CaptureChip extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool selected;
  final bool loading;
  final VoidCallback onTap;

  const _CaptureChip({
    required this.label,
    required this.icon,
    required this.selected,
    required this.onTap,
    this.loading = false,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final bgColor = selected ? colorScheme.primary : colorScheme.surface;
    final fgColor = selected ? colorScheme.onPrimary : colorScheme.onSurface;
    final borderColor =
        selected ? colorScheme.primary : colorScheme.outlineVariant;

    return GestureDetector(
      onTap: loading ? null : onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeInOut,
        padding:
            const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          color: bgColor,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: borderColor, width: selected ? 2 : 1),
          boxShadow: selected
              ? [
                  BoxShadow(
                      color: colorScheme.primary.withOpacity(0.25),
                      blurRadius: 6,
                      offset: const Offset(0, 2))
                ]
              : [],
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            loading
                ? SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: fgColor))
                : Icon(icon, size: 15, color: fgColor),
            const SizedBox(width: 6),
            Text(
              label,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: fgColor),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------

/// PermissionRow с замочком — показывает что разрешение включено и заблокировано
class _LockedPermissionRow extends StatelessWidget {
  const _LockedPermissionRow({
    required this.name,
    required this.isOk,
    required this.onPressed,
  });

  final String name;
  final bool isOk;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      visualDensity: VisualDensity.compact,
      contentPadding: EdgeInsets.zero,
      title: Row(children: [
        Expanded(child: Text(name)),
        const SizedBox(width: 4),
        Tooltip(
          message: translate("Managed via Accessibility Settings"),
          child: Icon(
            Icons.lock_outline,
            size: 14,
            color: Theme.of(context).colorScheme.primary.withOpacity(0.7),
          ),
        ),
      ]),
      // Ползунок убран намеренно. Замок рядом с названием был чисто
      // декоративным (Icon + Tooltip), а Switch оставался кликабельным и
      // вызывал toggleInput — операторы случайно выключали Input Control и
      // теряли удалённое управление телефоном. Теперь это только индикатор
      // состояния; включение/выключение — через системные Accessibility
      // Settings (о чём и говорит замок).
      trailing: Icon(
        isOk ? Icons.check_circle : Icons.error_outline,
        size: 20,
        color: isOk ? Colors.green : Theme.of(context).colorScheme.error,
      ),
    );
  }
}

class PermissionRow extends StatelessWidget {
  const PermissionRow(this.name, this.isOk, this.onPressed, {Key? key})
      : super(key: key);

  final String name;
  final bool isOk;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return SwitchListTile(
        visualDensity: VisualDensity.compact,
        contentPadding: EdgeInsets.all(0),
        title: Text(name),
        value: isOk,
        onChanged: (bool value) {
          onPressed();
        });
  }
}

class ConnectionManager extends StatelessWidget {
  const ConnectionManager({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final serverModel = Provider.of<ServerModel>(context);
    return Column(
        children: serverModel.clients
            .map((client) => PaddingCard(
                title: translate(
                    client.isFileTransfer ? "Transfer file" : "Share screen"),
                titleIcon: client.isFileTransfer
                    ? Icon(Icons.folder_outlined)
                    : Icon(Icons.mobile_screen_share),
                child: Column(children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(child: ClientInfo(client)),
                      Expanded(
                          flex: -1,
                          child: client.isFileTransfer || !client.authorized
                              ? const SizedBox.shrink()
                              : IconButton(
                                  onPressed: () {
                                    gFFI.chatModel.changeCurrentKey(
                                        MessageKey(client.peerId, client.id));
                                    final bar = navigationBarKey.currentWidget;
                                    if (bar != null) {
                                      bar as BottomNavigationBar;
                                      bar.onTap!(1);
                                    }
                                  },
                                  icon: unreadTopRightBuilder(
                                      client.unreadChatMessageCount)))
                    ],
                  ),
                  client.authorized
                      ? const SizedBox.shrink()
                      : Text(
                          translate("android_new_connection_tip"),
                          style: Theme.of(context).textTheme.bodyMedium,
                        ).marginOnly(bottom: 5),
                  client.authorized
                      ? _buildDisconnectButton(client)
                      : _buildNewConnectionHint(serverModel, client),
                  if (client.incomingVoiceCall && !client.inVoiceCall)
                    ..._buildNewVoiceCallHint(context, serverModel, client),
                ])))
            .toList());
  }

  Widget _buildDisconnectButton(Client client) {
    final disconnectButton = ElevatedButton.icon(
      style: ButtonStyle(backgroundColor: MaterialStatePropertyAll(Colors.red)),
      icon: const Icon(Icons.close),
      onPressed: () {
        bind.cmCloseConnection(connId: client.id);
        gFFI.invokeMethod("cancel_notification", client.id);
      },
      label: Text(translate("Disconnect")),
    );
    final buttons = [disconnectButton];
    if (client.inVoiceCall) {
      buttons.insert(
        0,
        ElevatedButton.icon(
          style: ButtonStyle(
              backgroundColor: MaterialStatePropertyAll(Colors.red)),
          icon: const Icon(Icons.phone),
          label: Text(translate("Stop")),
          onPressed: () {
            bind.cmCloseVoiceCall(id: client.id);
            gFFI.invokeMethod("cancel_notification", client.id);
          },
        ),
      );
    }

    if (buttons.length == 1) {
      return Container(
        alignment: Alignment.centerRight,
        child: disconnectButton,
      );
    } else {
      return Row(
        children: buttons,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
      );
    }
  }

  Widget _buildNewConnectionHint(ServerModel serverModel, Client client) {
    return Row(mainAxisAlignment: MainAxisAlignment.end, children: [
      TextButton(
          child: Text(translate("Dismiss")),
          onPressed: () {
            serverModel.sendLoginResponse(client, false);
          }).marginOnly(right: 15),
      if (serverModel.approveMode != 'password')
        ElevatedButton.icon(
            icon: const Icon(Icons.check),
            label: Text(translate("Accept")),
            onPressed: () {
              serverModel.sendLoginResponse(client, true);
            }),
    ]);
  }

  List<Widget> _buildNewVoiceCallHint(
      BuildContext context, ServerModel serverModel, Client client) {
    return [
      Text(
        translate("android_new_voice_call_tip"),
        style: Theme.of(context).textTheme.bodyMedium,
      ).marginOnly(bottom: 5),
      Row(mainAxisAlignment: MainAxisAlignment.end, children: [
        TextButton(
            child: Text(translate("Dismiss")),
            onPressed: () {
              serverModel.handleVoiceCall(client, false);
            }).marginOnly(right: 15),
        if (serverModel.approveMode != 'password')
          ElevatedButton.icon(
              icon: const Icon(Icons.check),
              label: Text(translate("Accept")),
              onPressed: () {
                serverModel.handleVoiceCall(client, true);
              }),
      ])
    ];
  }
}

class PaddingCard extends StatelessWidget {
  const PaddingCard({Key? key, required this.child, this.title, this.titleIcon})
      : super(key: key);

  final String? title;
  final Icon? titleIcon;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final children = [child];
    if (title != null) {
      children.insert(
          0,
          Padding(
              padding: const EdgeInsets.fromLTRB(0, 5, 0, 8),
              child: Row(
                children: [
                  titleIcon?.marginOnly(right: 10) ?? const SizedBox.shrink(),
                  Expanded(
                    child: Text(title!,
                        style: Theme.of(context)
                            .textTheme
                            .titleLarge
                            ?.merge(TextStyle(fontWeight: FontWeight.bold))),
                  )
                ],
              )));
    }
    return SizedBox(
        width: double.maxFinite,
        child: Card(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(13),
          ),
          margin: const EdgeInsets.fromLTRB(12.0, 10.0, 12.0, 0),
          child: Padding(
            padding:
                const EdgeInsets.symmetric(vertical: 15.0, horizontal: 20.0),
            child: Column(
              children: children,
            ),
          ),
        ));
  }
}

class ClientInfo extends StatelessWidget {
  final Client client;
  ClientInfo(this.client);

  @override
  Widget build(BuildContext context) {
    return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          Row(
            children: [
              Expanded(
                  flex: -1,
                  child: Padding(
                      padding: const EdgeInsets.only(right: 12),
                      child: CircleAvatar(
                          backgroundColor: str2color(
                              client.name,
                              Theme.of(context).brightness == Brightness.light
                                  ? 255
                                  : 150),
                          child: Text(client.name[0])))),
              Expanded(
                  child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                    Text(client.name, style: const TextStyle(fontSize: 18)),
                    const SizedBox(width: 8),
                    Text(client.peerId, style: const TextStyle(fontSize: 10))
                  ]))
            ],
          ),
        ]));
  }
}

void androidChannelInit() {
  gFFI.setMethodCallHandler((method, arguments) {
    debugPrint("flutter got android msg,$method,$arguments");
    try {
      switch (method) {
        case "start_capture":
          {
            gFFI.dialogManager.dismissAll();
            gFFI.serverModel.updateClientState();
            break;
          }
        case "on_state_changed":
          {
            var name = arguments["name"] as String;
            var value = arguments["value"] as String == "true";
            debugPrint("from jvm:on_state_changed,$name:$value");
            gFFI.serverModel.changeStatue(name, value);
            break;
          }
        case "on_android_permission_result":
          {
            var type = arguments["type"] as String;
            var result = arguments["result"] as bool;
            AndroidPermissionManager.complete(type, result);
            break;
          }
        case "on_media_projection_canceled":
          {
            gFFI.serverModel.stopService();
            break;
          }
        case "msgbox":
          {
            var type = arguments["type"] as String;
            var title = arguments["title"] as String;
            var text = arguments["text"] as String;
            var link = (arguments["link"] ?? '') as String;
            msgBox(gFFI.sessionId, type, title, text, link, gFFI.dialogManager);
            break;
          }
        case "stop_service":
          {
            print(
                "stop_service by kotlin, isStart:${gFFI.serverModel.isStart}");
            if (gFFI.serverModel.isStart) {
              gFFI.serverModel.stopService();
            }
            break;
          }
      }
    } catch (e) {
      debugPrintStack(label: "MethodCallHandler err:$e");
    }
    return "";
  });
}


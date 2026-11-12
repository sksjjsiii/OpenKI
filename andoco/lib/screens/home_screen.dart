import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:webview_flutter/webview_flutter.dart';
import '../services/opencode_bridge.dart';
import '../terminal/terminal_emulator.dart';
import '../terminal/terminal_view.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final OpencodeBridge _bridge = OpencodeBridge();
  late final WebViewController _webController;

  final List<_UiTermSession> _terms = [];
  int _activeTerm = 0;
  final List<String> _logBuffer = <String>[];
  StreamSubscription<LogEvent>? _logSub;

  int _tabIndex = 0;
  bool _bootstrapped = false;
  bool _serverReady = false;
  bool _loading = true;
  bool _webLoaded = false;
  bool _autoSwitchToWeb = false;
  double _fontSize = 13;
  String _status = 'در حال شروع...';
  bool _creatingSession = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _addLocalTerminal('main', title: 'اصلی', makeActive: true);
    _webController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF000000));
    _logSub = _bridge.logStream.listen(_onLogEvent);
    _init();
  }

  _UiTermSession _addLocalTerminal(String id, {String? title, bool makeActive = false}) {
    final emu = TerminalEmulator(cols: 300, rows: 60);
    final s = _UiTermSession(id: id, title: title ?? 'ترمینال ${_terms.length + 1}', emulator: emu);
    setState(() { _terms.add(s); if (makeActive) _activeTerm = _terms.length - 1; });
    return s;
  }

  _UiTermSession _getOrCreate(String id) {
    for (final t in _terms) { if (t.id == id) return t; }
    return _addLocalTerminal(id, title: id == 'main' ? 'اصلی' : 'ترمینال ${_terms.length + 1}', makeActive: false);
  }

  Future<void> _createNewTerminal() async {
    if (_creatingSession) return;
    setState(() => _creatingSession = true);
    try {
      final id = await _bridge.createSession();
      final t = _addLocalTerminal(id, title: 'ترمینال ${_terms.length}', makeActive: true);
      t.emulator.write('در حال اتصال به نشست جدید...\n');
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('ترمینال جدید ایجاد شد: $id')));
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('خطا در ایجاد ترمینال: $e')));
    } finally {
      if (mounted) setState(() => _creatingSession = false);
    }
  }

  Future<void> _closeTerminal(int index) async {
    if (_terms.length <= 1) return;
    final s = _terms[index];
    if (s.id != 'main') { try { await _bridge.closeSession(s.id); } catch (_) {} }
    setState(() { _terms.removeAt(index); _activeTerm = _activeTerm.clamp(0, _terms.length - 1); });
  }

  void _onLogEvent(LogEvent ev) {
    final line = ev.line;
    _logBuffer.add('[${ev.session}] $line');
    if (_logBuffer.length > 12000) _logBuffer.removeRange(0, _logBuffer.length - 12000);

    final t = _getOrCreate(ev.session);
    if (!(line.contains('__ANDOCO_SHELL_READY__') || line.contains('__ANDOCO_BOOTSTRAP_DONE__') || line.contains('__ANDOCO_BOOTSTRAP_FAILED__') || line.contains('__ANDOCO_STARTING_SERVER__'))) {
      t.emulator.write(line.endsWith('\n') ? '$line' : '$line\n');
    }
    if (mounted) setState(() {});

    if (line.startsWith('[وضعیت] ')) { final st = line.substring(8).trim(); if (mounted) setState(()=>_status=st); }
    else if (line.startsWith('[status] ')) { final st = line.substring(9).trim(); if (mounted) setState(()=>_status=st); }
    if (line.contains('__ANDOCO_BOOTSTRAP_DONE__')) { if (mounted) setState(()=>_bootstrapped=true); }
    if (!_serverReady && (line.contains('http://127.0.0.1:4096') || line.contains('OpenCode آماده'))) _onServerReady();
  }

  Future<void> _init() async {
    _getOrCreate('main').emulator.write('در حال اتصال به سرویس Andoco...\n');
    await _bridge.startService();
    await _requestPermissions();
    await _refreshState();
    _pollState();
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _requestPermissions() async {
    try { await [Permission.storage, Permission.notification].request(); } catch (_) {}
  }

  Future<void> _refreshState() async {
    final boot = await _bridge.isBootstrapped();
    final ready = await _bridge.isServerReady();
    final st = await _bridge.getStatus();
    if (!mounted) return;
    setState(() { _bootstrapped = boot; _serverReady = ready; if (st.isNotEmpty) _status = st; });
    if (ready && !_webLoaded) _loadWebView();
  }

  void _pollState() async {
    while (mounted) {
      await Future.delayed(const Duration(seconds: 2));
      if (!mounted) return;
      final boot = await _bridge.isBootstrapped();
      final ready = await _bridge.isServerReady();
      final st = await _bridge.getStatus();
      if (!mounted) return;
      setState(() { _bootstrapped = boot; if (st.isNotEmpty) _status = st; _serverReady = ready; });
      if (ready && !_webLoaded) _loadWebView();
    }
  }

  void _onServerReady() {
    if (!mounted) return;
    setState(() { _serverReady = true; _bootstrapped = true; if (_autoSwitchToWeb) _tabIndex = 1; });
    _loadWebView();
  }

  void _loadWebView() { if (_webLoaded) { try { _webController.reload(); } catch(_){ } return; } _webLoaded = true; _webController.loadRequest(Uri.parse('http://127.0.0.1:4096')); }

  Future<void> _reload() async {
    await _bridge.reload();
    setState(() { _serverReady = false; _webLoaded = false; _status = 'در حال راه‌اندازی مجدد نشست...'; });
    _getOrCreate('main').emulator.write('\n[وضعیت] درخواست راه‌اندازی مجدد نشست ارسال شد.\n');
  }

  Future<void> _copyAllLogs() async {
    await Clipboard.setData(ClipboardData(text: _logBuffer.join('\n')));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('همه لاگ‌ها کپی شد')));
  }

  void _sendToActive(String data) { if (_terms.isEmpty) return; _bridge.sendInput(data, session: _terms[_activeTerm].id); }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _logSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      _buildTerminalPage(),
      _buildWebPage(),
      FileExplorerPage(onOpenTerminal: () => setState(()=>_tabIndex=0)),
      _buildToolsPage(),
      _buildSettingsPage(),
      _buildAboutPage(),
    ];
    const titles = ['ترمینال', 'OpenCode', 'فایل‌ها', 'ابزارها', 'تنظیمات', 'درباره'];
    return Scaffold(
      appBar: AppBar(
        title: Text('Andoco — ${titles[_tabIndex]}'),
        actions: [
          if (_tabIndex == 0) IconButton(icon: _creatingSession ? const SizedBox(width:18,height:18,child:CircularProgressIndicator(strokeWidth:2)) : const Icon(Icons.add), tooltip: 'ترمینال جدید', onPressed: _createNewTerminal),
          IconButton(icon: const Icon(Icons.copy_all), tooltip: 'کپی همه لاگ‌ها', onPressed: _copyAllLogs),
          IconButton(icon: const Icon(Icons.refresh), tooltip: 'راه‌اندازی مجدد', onPressed: _reload),
        ],
      ),
      body: _loading ? const Center(child: CircularProgressIndicator()) : Column(children: [_buildStatusBar(), Expanded(child: pages[_tabIndex])]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tabIndex,
        onDestinationSelected: (i) => setState(()=>_tabIndex=i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.terminal), label: 'ترمینال'),
          NavigationDestination(icon: Icon(Icons.language), label: 'OpenCode'),
          NavigationDestination(icon: Icon(Icons.folder), label: 'فایل‌ها'),
          NavigationDestination(icon: Icon(Icons.build), label: 'ابزارها'),
          NavigationDestination(icon: Icon(Icons.settings), label: 'تنظیمات'),
          NavigationDestination(icon: Icon(Icons.info_outline), label: 'درباره'),
        ],
      ),
    );
  }

  Widget _buildStatusBar() {
    return Container(
      width: double.infinity, color: Theme.of(context).colorScheme.surfaceContainerHighest,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(children: [
        if (!_serverReady) const SizedBox(width:14,height:14,child:CircularProgressIndicator(strokeWidth:2)) else const Icon(Icons.check_circle,size:16,color:Colors.greenAccent),
        const SizedBox(width:8),
        Expanded(child: Directionality(textDirection: TextDirection.rtl, child: Text(_status, maxLines:2, overflow:TextOverflow.ellipsis, style: const TextStyle(fontSize:12)))),
      ]),
    );
  }

  Widget _buildTerminalPage() {
    if (_terms.isEmpty) return const SizedBox.shrink();
    final t = _terms[_activeTerm.clamp(0, _terms.length-1)];
    return Column(children: [
      Container(color: const Color(0xFF161B22), padding: const EdgeInsets.symmetric(horizontal:8, vertical:4), child: SingleChildScrollView(scrollDirection: Axis.horizontal, child: Row(children: [
        for (int i=0;i<_terms.length;i++) Padding(padding: const EdgeInsets.only(left:6), child: InputChip(label: Text(_terms[i].title), selected: i==_activeTerm, onPressed: ()=>setState(()=>_activeTerm=i), onDeleted: (_terms.length>1 && _terms[i].id!='main') ? ()=>_closeTerminal(i) : null)),
        ActionChip(label: const Text('+ جدید'), onPressed: _createNewTerminal),
      ]))),
      Expanded(child: CustomTerminalView(key: ValueKey(t.id), emulator: t.emulator, onInput: (d)=>_sendToActive(d), fontSize: _fontSize)),
      _buildQuickKeys(t.id),
    ]);
  }

  Widget _buildQuickKeys(String sessionId) {
    Widget k(String label, String seq) => Padding(padding: const EdgeInsets.symmetric(horizontal:2), child: InkWell(onTap: ()=>_bridge.sendInput(seq, session: sessionId), child: Container(padding: const EdgeInsets.symmetric(horizontal:10, vertical:8), decoration: BoxDecoration(color: const Color(0xFF21262D), borderRadius: BorderRadius.circular(6)), child: Text(label, style: const TextStyle(fontSize:12)))));
    return Container(color: const Color(0xFF0D1117), padding: const EdgeInsets.symmetric(horizontal:6, vertical:6), child: SafeArea(top:false, child: SingleChildScrollView(scrollDirection: Axis.horizontal, child: Row(children: [
      k('ESC','\x1b'), k('TAB','\t'), k('CTRL+C','\x03'), k('CTRL+D','\x04'), k('/','/'), k('-','-'), k('~','~'),
      k('HOME','\x1b[H'), k('END','\x1b[F'), k('↑','\x1b[A'), k('↓','\x1b[B'), k('←','\x1b[D'), k('→','\x1b[C'),
      TextButton.icon(onPressed: _copyAllLogs, icon: const Icon(Icons.copy,size:16), label: const Text('کپی لاگ')),
    ]))));
  }

  Widget _buildWebPage() {
    if (!_serverReady) {
      return Center(child: Padding(padding: const EdgeInsets.all(20), child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Icon(Icons.hourglass_top,size:40), const SizedBox(height:12),
        Directionality(textDirection: TextDirection.rtl, child: Text(_status, textAlign: TextAlign.center)),
        const SizedBox(height:12),
        ElevatedButton.icon(onPressed: ()=>setState(()=>_tabIndex=0), icon: const Icon(Icons.terminal), label: const Text('رفتن به ترمینال')),
      ])));
    }
    return WebViewWidget(controller: _webController);
  }

  Widget _buildToolsPage() {
    return Directionality(textDirection: TextDirection.rtl, child: ListView(padding: const EdgeInsets.all(16), children: [
      const Text('ابزارها و تعمیر خودکار', style: TextStyle(fontSize:20,fontWeight: FontWeight.bold)),
      const SizedBox(height:12),
      Card(child: ListTile(leading: const Icon(Icons.auto_fix_high), title: const Text('تعمیر و تکمیل نصب (بدون حذف داده)'), subtitle: const Text('رفع dpkg/perl، نصب Node/OpenCode، تلاش مجدد npm'), onTap: () { setState(()=>_tabIndex=0); _sendToActive("dpkg --configure -a --force-confdef --force-confold --force-overwrite || true; apt-get -o APT::Sandbox::User=root -f install -y || true; apt-get -o APT::Sandbox::User=root update || true; command -v npm >/dev/null 2>&1 || (curl -fsSL https://nodejs.org/dist/v20.18.0/node-v20.18.0-linux-arm64.tar.xz -o /tmp/n.tgz && tar -xJf /tmp/n.tgz -C /usr/local --strip-components=1); npm config set cache /root/.npm-andoco; rm -rf /root/.npm-andoco/_cacache/tmp || true; npm cache verify || npm cache clean --force || true; npm install -g opencode-termux --no-audit --no-fund || npm install -g opencode-ai --no-audit --no-fund || true; hash -r; opencode --version 2>&1 || echo done\n"); })),
      _toolCard('عیب‌یابی/نصب', [
        _cmd('وضعیت شبکه','getent hosts registry.npmjs.org || ping -c1 1.1.1.1 || echo no-network'),
        _cmd('نسخه‌ها','node -v 2>&1; npm -v 2>&1; opencode --version 2>&1 || echo no-opencode'),
        _cmd('رفع dpkg کامل','dpkg --configure -a --force-confdef --force-confold --force-overwrite || true; apt-get -o APT::Sandbox::User=root -f install -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Options::=--force-overwrite || true'),
        _cmd('نصب پایه apt','apt-get -o APT::Sandbox::User=root update && apt-get -o APT::Sandbox::User=root install -y --no-install-recommends ca-certificates curl git xz-utils tar nano'),
        _cmd('نصب Node باینری','mkdir -p /tmp && curl -fL --retry 4 https://nodejs.org/dist/v20.18.0/node-v20.18.0-linux-arm64.tar.xz -o /tmp/node.tar.xz && tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 && node -v && npm -v'),
        _cmd('نصب OpenCode','rm -rf /root/.npm-andoco/_cacache/tmp || true; npm cache clean --force || true; npm install -g opencode-termux --no-audit --no-fund --cache /root/.npm-andoco || npm install -g opencode-ai --no-audit --no-fund --cache /root/.npm-andoco'),
        _cmd('اجرای OpenCode','opencode web --port 4096 --hostname 127.0.0.1 --print-logs --log-level INFO --pure'),
      ]),
      _toolCard('سیستم', [
        _cmd('uname/os','uname -a; echo; cat /etc/os-release 2>/dev/null | head -20'),
        _cmd('فضای دیسک','df -h'),
        _cmd('فرآیندها','ps aux 2>/dev/null | head -30 || ps -ef | head -30'),
        _cmd('env','env | sort | head -50'),
      ]),
      Card(child: ListTile(leading: const Icon(Icons.refresh), title: const Text('راه‌اندازی مجدد نشست سرویس'), onTap: _reload)),
      Card(child: ListTile(leading: const Icon(Icons.copy_all), title: const Text('کپی همه لاگ‌ها'), onTap: _copyAllLogs)),
    ]));
  }

  Widget _toolCard(String title, List<Widget> chips) => Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(title, style: const TextStyle(fontWeight: FontWeight.bold)), const SizedBox(height:8), Wrap(spacing:8, runSpacing:8, children: chips)])));
  Widget _cmd(String label, String cmd) => ActionChip(label: Text(label), onPressed: () { setState(()=>_tabIndex=0); _sendToActive('$cmd\n'); });

  Widget _buildSettingsPage() {
    return Directionality(textDirection: TextDirection.rtl, child: ListView(padding: const EdgeInsets.all(16), children: [
      const Text('تنظیمات', style: TextStyle(fontSize:20,fontWeight: FontWeight.bold)), const SizedBox(height:12),
      Card(child: SwitchListTile(title: const Text('جابجایی خودکار به OpenCode پس از آماده شدن'), value: _autoSwitchToWeb, onChanged: (v)=>setState(()=>_autoSwitchToWeb=v))),
      Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('اندازه فونت ترمینال: ${_fontSize.toStringAsFixed(0)}'), Slider(min:10,max:28,divisions:18,value:_fontSize,onChanged:(v)=>setState(()=>_fontSize=v))]))),
      Card(child: ListTile(leading: const Icon(Icons.folder_open), title: const Text('تنظیمات مجوز فایل/حافظه'), onTap: () async { await openAppSettings(); })),
      Card(child: ListTile(leading: const Icon(Icons.security), title: const Text('درخواست مجوز ذخیره‌سازی'), onTap: () async { await [Permission.storage, Permission.manageExternalStorage].request(); })),
      Card(child: ListTile(leading: const Icon(Icons.refresh), title: const Text('راه‌اندازی مجدد نشست'), onTap: _reload)),
      Card(child: ListTile(leading: const Icon(Icons.stop_circle_outlined), title: const Text('توقف سرویس'), onTap: () async { await _bridge.stopService(); })),
      Card(child: ListTile(leading: const Icon(Icons.copy_all), title: const Text('کپی همه لاگ‌ها'), onTap: _copyAllLogs)),
      const Card(child: Padding(padding: EdgeInsets.all(12), child: Text('نکات:\n• ترمینال سفارشی: همیشه چپ‌چین، اسکرول افقی/عمودی برای جلوگیری از تکه‌تکه شدن خطوط طولانی.\n• رفع خطاها بدون حذف داده: بوت‌استرپ در هر اجرا محیط موجود را تعمیر می‌کند (dpkg/apt/chown-wrapper، npm cache در مسیر پایدار، نصب OpenCode با تلاش مجدد) و rootfs را پاک نمی‌کند.\n• تب‌های ترمینال جدید نشست‌های واقعی جداگانه در محیط proot هستند.'))),
    ]));
  }

  Widget _buildAboutPage() {
    return Directionality(textDirection: TextDirection.rtl, child: ListView(padding: const EdgeInsets.all(16), children: const [
      Text('درباره Andoco', style: TextStyle(fontSize:20,fontWeight: FontWeight.bold)), SizedBox(height:12),
      Card(child: ListTile(title: Text('محیط'), subtitle: Text('Debian arm64 در proot + OpenCode Web'))),
      Card(child: ListTile(title: Text('ترمینال سفارشی'), subtitle: Text('پیاده‌سازی داخلی (بدون کتابخانه xterm)، ANSI/SGR، اسکرول‌بک، ورودی تعاملی، تب‌های ترمینال جدید، کلیدهای میانبر و نمایش پایدار چپ‌چین.'))),
      Card(child: ListTile(title: Text('امکانات'), subtitle: Text('پیشرفت زنده، وب‌ویو OpenCode، فایل‌اکسپلورر کامل، ابزارهای تعمیر/نصب، تنظیمات، کپی لاگ، دسترسی حافظه (مطابق مجوزها).'))),
    ]));
  }
}

class _UiTermSession {
  final String id;
  final String title;
  final TerminalEmulator emulator;
  _UiTermSession({required this.id, required this.title, required this.emulator});
}

class FileExplorerPage extends StatefulWidget {
  final VoidCallback? onOpenTerminal;
  const FileExplorerPage({super.key, this.onOpenTerminal});
  @override
  State<FileExplorerPage> createState() => _FileExplorerPageState();
}

class _FileExplorerPageState extends State<FileExplorerPage> {
  late Directory _current;
  List<FileSystemEntity> _entries = [];
  bool _loading = true;
  String? _error;
  bool _showHidden = false;
  String _query = '';
  final TextEditingController _searchCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _current = Directory('/storage/emulated/0');
    if (!_current.existsSync()) _current = Directory('/sdcard');
    if (!_current.existsSync()) _current = Directory('/data/data/com.andoco.app/files');
    if (!_current.existsSync()) _current = Directory('/');
    _refresh();
  }

  @override
  void dispose() { _searchCtrl.dispose(); super.dispose(); }

  Future<void> _refresh() async {
    setState(() { _loading = true; _error = null; });
    try {
      final list = _current.listSync();
      list.sort((a,b){ final ad=a is Directory?0:1; final bd=b is Directory?0:1; if(ad!=bd) return ad.compareTo(bd); return a.path.toLowerCase().compareTo(b.path.toLowerCase()); });
      var filtered = _showHidden ? list : list.where((e)=>!_nameOf(e).startsWith('.')).toList();
      if (_query.isNotEmpty) { final q=_query.toLowerCase(); filtered = filtered.where((e)=>_nameOf(e).toLowerCase().contains(q)).toList(); }
      setState(() { _entries = filtered; _loading = false; });
    } catch (e) { setState(() { _error = e.toString(); _loading = false; }); }
  }

  String _nameOf(FileSystemEntity e) { final segs = e.uri.pathSegments.where((s)=>s.isNotEmpty).toList(); return segs.isEmpty ? e.path : segs.last; }
  void _goUp() { final p = _current.parent; if (p.path == _current.path) return; setState(()=>_current=p); _refresh(); }
  void _openEntity(FileSystemEntity e) { if (e is Directory) { setState((){_current=e; _query=''; _searchCtrl.clear();}); _refresh(); } else if (e is File) { _openFile(e); } }

  Future<void> _openFile(File f) async {
    try {
      final stat = await f.stat(); const max = 1024 * 1024;
      final bytes = await f.openRead(0, max).fold<List<int>>([], (p,e)=>p..addAll(e));
      String text; try { text = utf8.decode(bytes, allowMalformed: true); } catch (_) { text = bytes.map((b)=>b.toRadixString(16).padLeft(2,'0')).join(' '); }
      if (!mounted) return;
      showModalBottomSheet(context: context, isScrollControlled: true, builder: (ctx) {
        return Directionality(textDirection: TextDirection.rtl, child: SafeArea(child: SizedBox(height: MediaQuery.of(ctx).size.height*0.85, child: Column(children: [
          ListTile(title: Text(f.path, maxLines:2, overflow: TextOverflow.ellipsis), subtitle: Text('اندازه: ${stat.size} بایت${stat.size>max ? " (نمایش بخشی)" : ""}'), trailing: Wrap(children: [IconButton(icon: const Icon(Icons.copy), onPressed: () async { await Clipboard.setData(ClipboardData(text: text)); if(mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('کپی شد'))); }), IconButton(icon: const Icon(Icons.link), onPressed: () async { await Clipboard.setData(ClipboardData(text: f.path)); })])),
          const Divider(height:1),
          Expanded(child: SingleChildScrollView(padding: const EdgeInsets.all(12), child: Directionality(textDirection: _looksRtl(text)?TextDirection.rtl:TextDirection.ltr, child: SelectableText(text, style: const TextStyle(fontFamily:'monospace', fontSize:12))))),
        ]))));
      });
    } catch (e) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('خطا: $e'))); }
  }

  Future<void> _jumpTo(String path) async { final d = Directory(path); if (await d.exists()) { setState((){_current=d; _query=''; _searchCtrl.clear();}); _refresh(); } else { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('مسیر در دسترس نیست: $path'))); } }
  Future<void> _deleteEntity(FileSystemEntity e) async { final ok = await showDialog<bool>(context: context, builder: (ctx)=>Directionality(textDirection: TextDirection.rtl, child: AlertDialog(title: const Text('حذف'), content: Text('حذف ${_nameOf(e)}؟'), actions:[TextButton(onPressed: ()=>Navigator.pop(ctx,false), child: const Text('لغو')), TextButton(onPressed: ()=>Navigator.pop(ctx,true), child: const Text('حذف'))]))); if (ok != true) return; try { await e.delete(recursive: e is Directory); _refresh(); } catch (err) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('خطا: $err'))); } }
  Future<void> _renameEntity(FileSystemEntity e) async { final ctrl = TextEditingController(text: _nameOf(e)); final ok = await showDialog<bool>(context: context, builder: (ctx)=>Directionality(textDirection: TextDirection.rtl, child: AlertDialog(title: const Text('تغییر نام'), content: TextField(controller: ctrl), actions:[TextButton(onPressed: ()=>Navigator.pop(ctx,false), child: const Text('لغو')), TextButton(onPressed: ()=>Navigator.pop(ctx,true), child: const Text('تایید'))]))); if (ok != true || ctrl.text.trim().isEmpty) return; try { await e.rename('${_current.path}/${ctrl.text.trim()}'); _refresh(); } catch (err) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('خطا: $err'))); } }
  Future<void> _createFolder() async { final c=TextEditingController(); final ok=await showDialog<bool>(context: context,builder:(ctx)=>Directionality(textDirection: TextDirection.rtl,child:AlertDialog(title: const Text('پوشه جدید'), content: TextField(controller:c), actions:[TextButton(onPressed:()=>Navigator.pop(ctx,false),child:const Text('لغو')),TextButton(onPressed:()=>Navigator.pop(ctx,true),child:const Text('ایجاد'))]))); if(ok==true && c.text.trim().isNotEmpty){ try{await Directory('${_current.path}/${c.text.trim()}').create(); _refresh();}catch(e){ if(mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('خطا: $e')));} } }
  Future<void> _createFile() async { final c=TextEditingController(); final ok=await showDialog<bool>(context: context,builder:(ctx)=>Directionality(textDirection: TextDirection.rtl,child:AlertDialog(title: const Text('فایل جدید'), content: TextField(controller:c), actions:[TextButton(onPressed:()=>Navigator.pop(ctx,false),child:const Text('لغو')),TextButton(onPressed:()=>Navigator.pop(ctx,true),child:const Text('ایجاد'))]))); if(ok==true && c.text.trim().isNotEmpty){ try{await File('${_current.path}/${c.text.trim()}').create(); _refresh();}catch(e){ if(mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('خطا: $e')));} } }

  Future<void> _showEntryActions(FileSystemEntity e) async {
    await showModalBottomSheet(context: context, builder: (ctx)=>SafeArea(child: Directionality(textDirection: TextDirection.rtl, child: Column(mainAxisSize: MainAxisSize.min, children: [
      ListTile(title: Text(_nameOf(e)), subtitle: Text(e.path, maxLines:2, overflow: TextOverflow.ellipsis)),
      ListTile(leading: const Icon(Icons.copy), title: const Text('کپی مسیر'), onTap: () async { await Clipboard.setData(ClipboardData(text: e.path)); if(mounted) Navigator.pop(ctx); }),
      if (e is File) ListTile(leading: const Icon(Icons.open_in_new), title: const Text('مشاهده'), onTap: (){ Navigator.pop(ctx); _openFile(e); }),
      if (e is Directory) ListTile(leading: const Icon(Icons.folder_open), title: const Text('باز کردن'), onTap: (){ Navigator.pop(ctx); _openEntity(e); }),
      ListTile(leading: const Icon(Icons.drive_file_rename_outline), title: const Text('تغییر نام'), onTap: (){ Navigator.pop(ctx); _renameEntity(e); }),
      ListTile(leading: const Icon(Icons.info_outline), title: const Text('اطلاعات'), onTap: () async { try { final st = await e.stat(); if(!mounted) return; Navigator.pop(ctx); showDialog(context: context, builder: (dctx)=>Directionality(textDirection: TextDirection.rtl, child: AlertDialog(title: const Text('اطلاعات'), content: SelectableText('مسیر: ${e.path}\nنوع: ${st.type}\nاندازه: ${st.size}\nتغییر: ${st.modified}\nدسترسی: ${st.modeString()}'), actions:[TextButton(onPressed: ()=>Navigator.pop(dctx), child: const Text('بستن'))]))); } catch(_) { if(mounted) Navigator.pop(ctx);} }),
      ListTile(leading: const Icon(Icons.delete_outline), title: const Text('حذف'), onTap: (){ Navigator.pop(ctx); _deleteEntity(e); }),
    ]))));
  }

  @override
  Widget build(BuildContext context) {
    return Directionality(textDirection: TextDirection.rtl, child: Column(children: [
      Material(color: Theme.of(context).colorScheme.surfaceContainer, child: Padding(padding: const EdgeInsets.symmetric(horizontal:8, vertical:6), child: Column(children: [
        Row(children: [IconButton(icon: const Icon(Icons.arrow_upward), onPressed:_goUp), IconButton(icon: const Icon(Icons.refresh), onPressed:_refresh), IconButton(icon: Icon(_showHidden?Icons.visibility_off:Icons.visibility), onPressed:(){ setState(()=>_showHidden=!_showHidden); _refresh(); }), IconButton(icon: const Icon(Icons.create_new_folder), onPressed:_createFolder), IconButton(icon: const Icon(Icons.note_add), onPressed:_createFile), const Spacer(), IconButton(icon: const Icon(Icons.terminal), onPressed: widget.onOpenTerminal)]),
        SingleChildScrollView(scrollDirection: Axis.horizontal, child: Row(children: [for (final p in ['/sdcard','/storage/emulated/0','/storage','/data/data/com.andoco.app','/data/data/com.andoco.app/files','/']) Padding(padding: const EdgeInsets.only(left:6), child: ActionChip(label: Text(p), onPressed: ()=>_jumpTo(p)))])),
        const SizedBox(height:6), TextField(controller: _searchCtrl, decoration: const InputDecoration(isDense:true, prefixIcon: Icon(Icons.search), hintText:'جستجو...', border: OutlineInputBorder()), onChanged: (v){ _query=v; _refresh(); }), const SizedBox(height:4),
        Align(alignment: Alignment.centerRight, child: SelectableText(_current.path, style: const TextStyle(fontFamily:'monospace', fontSize:12))),
      ]))),
      const Divider(height:1),
      Expanded(child: _loading ? const Center(child:CircularProgressIndicator()) : _error!=null ? Center(child: Padding(padding: const EdgeInsets.all(16), child: Text('خطا/عدم دسترسی: $_error\n\nممکن است نیاز به اعطای مجوز فایل از تنظیمات سیستم باشد.', textAlign: TextAlign.center))) : _entries.isEmpty ? const Center(child: Text('موردی برای نمایش نیست')) : ListView.builder(itemCount: _entries.length, itemBuilder: (context,i){ final e=_entries[i]; final isDir=e is Directory; final name=_nameOf(e); return ListTile(leading: Icon(isDir?Icons.folder:Icons.insert_drive_file), title: Text(name, textDirection: _looksRtl(name)?TextDirection.rtl:TextDirection.ltr), subtitle: Text(isDir?'پوشه':_fileSubtitle(e), style: const TextStyle(fontSize:11)), onTap: ()=>_openEntity(e), onLongPress: ()=>_showEntryActions(e), trailing: IconButton(icon: const Icon(Icons.more_vert), onPressed: ()=>_showEntryActions(e))); })),
    ]));
  }

  bool _looksRtl(String s){ for(final r in s.runes){ if((r>=0x0600&&r<=0x06FF)||(r>=0x0750&&r<=0x077F)||(r>=0xFB50&&r<=0xFDFF)||(r>=0xFE70&&r<=0xFEFF)) return true; } return false; }
  String _fileSubtitle(FileSystemEntity e){ try{ final st=e.statSync(); return st.size>=1024*1024 ? '${(st.size/(1024*1024)).toStringAsFixed(2)} MB' : '${(st.size/1024).toStringAsFixed(1)} KB'; }catch(_){ return 'فایل'; } }
}

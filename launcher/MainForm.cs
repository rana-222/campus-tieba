using System.Diagnostics;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace TiebaLauncher;

/// <summary>
/// 启动器主窗口：先显示启动进度（杀端口 → 起后端 → 等就绪），
/// 就绪后切换为 WebView2 浏览器窗口加载 http://localhost:8080/。
/// 窗口关闭时结束整个后端进程树。
/// </summary>
public sealed class MainForm : Form
{
    private const string AppUrl = "http://localhost:8080/";
    private const string ProbeUrl = "http://127.0.0.1:8080/";
    private const int Port = 8080;
    private static readonly TimeSpan StartTimeout = TimeSpan.FromSeconds(240);

    private readonly Label _titleLabel = new();
    private readonly Label _statusLabel = new();
    private readonly Button _cancelButton = new();
    private readonly System.Windows.Forms.Timer _statusTimer = new();

    private WebView2? _browser;
    private Process? _backend;
    private CancellationTokenSource? _cts;
    private volatile bool _shuttingDown;
    private bool _browserMode;
    private static readonly object LogFileLock = new();

    public MainForm()
    {
        Text = "校园贴吧";
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(420, 180);
        Font = new Font("Microsoft YaHei UI", 9F);
        BackColor = Color.White;

        _titleLabel.Text = "校园贴吧";
        _titleLabel.Font = new Font("Microsoft YaHei UI", 22F, FontStyle.Bold);
        _titleLabel.ForeColor = Color.FromArgb(35, 99, 78);
        _titleLabel.TextAlign = ContentAlignment.MiddleCenter;
        _titleLabel.SetBounds(0, 40, 420, 40);

        _statusLabel.Text = "正在启动…";
        _statusLabel.ForeColor = Color.FromArgb(120, 120, 120);
        _statusLabel.Font = new Font("Microsoft YaHei UI", 11F);
        _statusLabel.TextAlign = ContentAlignment.MiddleCenter;
        _statusLabel.SetBounds(0, 96, 420, 26);

        _cancelButton.Text = "取消";
        _cancelButton.FlatStyle = FlatStyle.Flat;
        _cancelButton.ForeColor = Color.FromArgb(120, 120, 120);
        _cancelButton.SetBounds(164, 138, 92, 30);
        _cancelButton.Click += (_, _) => Close();

        Controls.Add(_titleLabel);
        Controls.Add(_statusLabel);
        Controls.Add(_cancelButton);

        _statusTimer.Interval = 2500;
        _statusTimer.Tick += (_, _) => CycleStatusText();
        _statusTimer.Start();

        Shown += async (_, _) => await RunStartupAsync();
    }

    // ---------------- 启动流程 ----------------

    private async Task RunStartupAsync()
    {
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;
        try
        {
            SetStatus("正在定位项目目录", "正在启动…");
            string? root = FindProjectRoot(AppDomain.CurrentDomain.BaseDirectory);
            if (root is null)
            {
                Fail("应用文件缺失，请重新安装后重试。");
                return;
            }
            AppendLog("项目目录: " + root);

            SetStatus($"检查端口 {Port}", "正在启动…");
            var killed = KillPortOccupants(Port);
            foreach (var pid in killed)
                AppendLog($"已结束占用端口 {Port} 的进程 PID {pid}");

            SetStatus("启动后端服务 mvn clean spring-boot:run", "正在启动…");
            _backend = StartBackend(root);
            if (_backend is null)
            {
                Fail("缺少运行环境，请重新安装后重试。");
                return;
            }
            _backend.EnableRaisingEvents = true;
            _backend.Exited += BackendExited;
            AppendLog($"后端进程已启动 (PID {_backend.Id})，等待就绪…");

            SetStatus("等待后端就绪", "正在启动…");
            AppendLog("开始探测 " + ProbeUrl);
            using var http = new HttpClient();
            var sw = Stopwatch.StartNew();
            bool ready = false;
            int probe = 0;
            while (sw.Elapsed < StartTimeout)
            {
                ct.ThrowIfCancellationRequested();
                if (_backend.HasExited)
                {
                    Fail("启动失败，请稍后重试。\n若持续失败，请联系管理员检查数据库与端口配置。");
                    return;
                }
                probe++;
                try
                {
                    // 每次探测单独 3 秒超时；探测超时按“未就绪”处理，不中断流程
                    using var tryCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    tryCts.CancelAfter(TimeSpan.FromSeconds(3));
                    using var resp = await http.GetAsync(ProbeUrl, tryCts.Token);
                    AppendLog($"探测#{probe}: HTTP {(int)resp.StatusCode}");
                    if (resp.IsSuccessStatusCode) { ready = true; break; }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw; // 用户主动取消
                }
                catch (Exception ex)
                {
                    AppendLog($"探测#{probe}: {ex.GetType().Name}");
                }
                SetStatus("正在启动…");
                await Task.Delay(1500, ct);
            }
            if (!ready)
            {
                Fail("启动超时，请稍后重试。\n若持续失败，请联系管理员。");
                return;
            }

            AppendLog("后端已就绪，正在打开界面…");
            await SwitchToBrowserAsync();
        }
        catch (OperationCanceledException)
        {
            // 用户取消，交给 OnFormClosing 清理
        }
        catch (Exception ex)
        {
            Fail("启动失败，请稍后重试。\n若持续失败，请联系管理员。");
        }
    }

    // ---------------- 切换为浏览器窗口 ----------------

    private async Task SwitchToBrowserAsync()
    {
        _statusTimer.Stop();
        FileLog("后端就绪，切换浏览器模式");
        _browserMode = true;
        SuspendLayout();
        Controls.Clear();
        FormBorderStyle = FormBorderStyle.Sizable;
        MaximizeBox = true;
        MinimizeBox = true;
        ClientSize = new Size(1280, 840);
        CenterToScreen();
        _browser = new WebView2 { Dock = DockStyle.Fill };
        Controls.Add(_browser);
        ResumeLayout();

        try
        {
            await _browser.EnsureCoreWebView2Async();
        }
        catch (WebView2RuntimeNotFoundException)
        {
            Fail("浏览器组件初始化失败，请安装 Edge WebView2 运行时后重试。");
            return;
        }

        _browser.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = true;
        _browser.Source = new Uri(AppUrl);
    }

    // ---------------- 后端进程管理 ----------------

    private Process? StartBackend(string root)
    {
        ProcessStartInfo psi;
        bool hasPom = File.Exists(Path.Combine(root, "pom.xml"));
        string? mvn = hasPom ? (ResolveOnPath("mvn.cmd") ?? ResolveOnPath("mvn")) : null;
        if (mvn is not null)
        {
            AppendLog("启动方式: mvn clean spring-boot:run");
            psi = new ProcessStartInfo("cmd.exe", "/c \"\"" + mvn + "\" clean spring-boot:run\"");
        }
        else
        {
            // 备选：直接跑已构建的 fat jar（优先 target/，其次根目录，便于分发）
            var searchDirs = new[]
            {
                Path.Combine(root, "target"),
                root
            };
            FileInfo? jar = null;
            foreach (var dir in searchDirs)
            {
                var di = new DirectoryInfo(dir);
                if (!di.Exists) continue;
                jar = di.EnumerateFiles("*.jar")
                    .Where(f => !f.Name.EndsWith(".original", StringComparison.OrdinalIgnoreCase))
                    .OrderByDescending(f => f.LastWriteTime)
                    .FirstOrDefault();
                if (jar is not null) break;
            }
            string? java = ResolveJava();
            if (java is null || jar is null) return null;
            AppendLog("启动方式: java -jar " + jar.Name);
            psi = new ProcessStartInfo(java, "-jar \"" + jar.FullName + "\"");
        }

        psi.WorkingDirectory = root;
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        psi.RedirectStandardOutput = true;
        psi.RedirectStandardError = true;

        var p = Process.Start(psi);
        if (p is null) return null;
        p.OutputDataReceived += (_, e) => { if (e.Data is not null) AppendLog(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (e.Data is not null) AppendLog(e.Data); };
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        return p;
    }

    private void BackendExited(object? sender, EventArgs e)
    {
        if (_shuttingDown || !_browserMode) return; // 启动阶段由轮询逻辑处理
        if (IsDisposed || !IsHandleCreated) return;
        try
        {
            BeginInvoke(() =>
            {
                if (_shuttingDown || IsDisposed) return;
                MessageBox.Show(this, "后端服务已退出，应用即将关闭。", "校园贴吧",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                _shuttingDown = true;
                Close();
            });
        }
        catch (ObjectDisposedException) { }
    }

    private void KillBackend()
    {
        try
        {
            if (_backend is { HasExited: false })
            {
                _backend.Kill(entireProcessTree: true);
                _backend.WaitForExit(5000);
            }
        }
        catch { /* 进程可能已自行退出 */ }
    }

    // ---------------- 端口清理 ----------------

    /// <summary>结束所有监听指定端口的进程（IPv4/IPv6 去重），返回被结束的 PID 列表。</summary>
    private static List<int> KillPortOccupants(int port)
    {
        var victims = new List<int>();
        try
        {
            var psi = new ProcessStartInfo("netstat.exe", "-ano")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true
            };
            using var p = Process.Start(psi);
            if (p is null) return victims;
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(5000);

            foreach (var line in output.Split('\n'))
            {
                var cols = line.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (cols.Length < 5 || !cols[3].Equals("LISTENING", StringComparison.OrdinalIgnoreCase)) continue;
                if (!cols[1].EndsWith(":" + port, StringComparison.Ordinal)) continue;
                if (!int.TryParse(cols[4], out int pid) || pid == 0) continue;
                if (pid == Environment.ProcessId || victims.Contains(pid)) continue;
                victims.Add(pid);
            }

            foreach (var pid in victims)
            {
                try
                {
                    var proc = Process.GetProcessById(pid);
                    proc.Kill(entireProcessTree: true);
                    proc.WaitForExit(5000);
                }
                catch { /* 进程可能已退出或无权限 */ }
            }
        }
        catch { }
        return victims;
    }

    // ---------------- 工具方法 ----------------

    private static string? FindProjectRoot(string startDir)
    {
        // 优先：包含 pom.xml 的目录（开发环境）
        var dir = new DirectoryInfo(startDir);
        for (int i = 0; i < 6 && dir is not null; i++)
        {
            if (File.Exists(Path.Combine(dir.FullName, "pom.xml"))) return dir.FullName;
            dir = dir.Parent;
        }
        // 备选：包含 fat jar 的目录（分发包）
        var root = new DirectoryInfo(startDir);
        if (root.EnumerateFiles("*.jar").Any(f => !f.Name.EndsWith(".original", StringComparison.OrdinalIgnoreCase)))
            return root.FullName;
        return null;
    }

    /// <summary>解析 java：优先 JAVA_HOME（与 mvn 编译保持一致），其次 PATH。</summary>
    private static string? ResolveJava()
    {
        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(javaHome))
        {
            var fromHome = Path.Combine(javaHome, "bin", "java.exe");
            if (File.Exists(fromHome)) return fromHome;
        }
        return ResolveOnPath("java.exe");
    }

    private static string? ResolveOnPath(string exe)
    {
        try
        {
            var psi = new ProcessStartInfo("where.exe", exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true
            };
            using var p = Process.Start(psi);
            if (p is null) return null;
            string output = p.StandardOutput.ReadToEnd();
            p.WaitForExit(5000);
            return output.Split('\n')
                .Select(l => l.Trim())
                .FirstOrDefault(l => l.Length > 0 && File.Exists(l));
        }
        catch { return null; }
    }

    private static readonly string[] StatusMessages =
    {
        "正在启动…",
        "正在连接数据库…",
        "正在加载版面…",
        "马上就好…"
    };
    private int _statusIndex;

    private void CycleStatusText()
    {
        if (IsDisposed || _browserMode) return;
        _statusIndex = (_statusIndex + 1) % StatusMessages.Length;
        _statusLabel.Text = StatusMessages[_statusIndex];
    }

    private void SetStatus(string logText, string? displayText = null)
    {
        FileLog("[状态] " + logText);
        if (IsDisposed) return;
        _statusLabel.Text = displayText ?? logText;
    }

    private static string LogFilePath => Path.Combine(Path.GetTempPath(), "tieba-launcher.log");

    /// <summary>诊断日志：写入文件（%TEMP%\tieba-launcher.log），界面不显示编译过程。</summary>
    private static void FileLog(string line)
    {
        try
        {
            lock (LogFileLock)
            {
                File.AppendAllText(LogFilePath,
                    DateTime.Now.ToString("HH:mm:ss.fff") + " " + line + Environment.NewLine);
            }
        }
        catch { /* 日志失败不影响主流程 */ }
    }

    private void AppendLog(string line) => FileLog(line);

    private void Fail(string message)
    {
        _statusTimer.Stop();
        FileLog("[失败] " + message.Replace("\n", " | "));
        if (IsDisposed) return;
        SetStatus("启动失败", "启动失败");
        _cancelButton.Text = "关闭";
        MessageBox.Show(this, message, "校园贴吧", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }

    // ---------------- 关闭清理 ----------------

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _shuttingDown = true;
        _cts?.Cancel();
        KillBackend();
        base.OnFormClosing(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _statusTimer.Dispose();
            _cts?.Dispose();
            _backend?.Dispose();
        }
        base.Dispose(disposing);
    }
}

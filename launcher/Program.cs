namespace TiebaLauncher;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        bool firstInstance;
        using var mutex = new Mutex(true, "CampusTieba.Launcher.SingleInstance", out firstInstance);
        if (!firstInstance)
        {
            MessageBox.Show("校园贴吧已在运行中。", "校园贴吧", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

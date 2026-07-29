using System.Windows;

namespace AiLiveEdge.Desktop;

public enum CloseAgentChoice
{
    ContinueInBackground,
    StopAgent,
    Cancel
}

public partial class CloseAgentDialog : Window
{
    public CloseAgentDialog()
    {
        InitializeComponent();
    }

    public CloseAgentChoice Choice { get; private set; } = CloseAgentChoice.Cancel;

    private void Confirm_Click(object sender, RoutedEventArgs e)
    {
        Choice = StopRadio.IsChecked == true
            ? CloseAgentChoice.StopAgent
            : CloseAgentChoice.ContinueInBackground;
        DialogResult = true;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        Choice = CloseAgentChoice.Cancel;
        DialogResult = false;
    }
}

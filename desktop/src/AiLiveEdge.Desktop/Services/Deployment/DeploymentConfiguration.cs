namespace AiLiveEdge.Desktop.Services.Deployment;

public sealed record DeploymentConfiguration(
    DeploymentMode DeploymentMode,
    string ApiBaseUrl,
    bool AllowServerEditing,
    IReadOnlyList<string> AllowedHosts)
{
    public static DeploymentConfiguration Default { get; } = new(
        DeploymentMode.SAAS,
        string.Empty,
        false,
        []);
}

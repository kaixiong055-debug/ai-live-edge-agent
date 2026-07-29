namespace AiLiveEdge.Desktop.Models;

public enum AgentHealthState
{
    Healthy,
    Unavailable,
    PortOccupiedByOtherService
}

public sealed record AgentHealthResult(
    AgentHealthState State,
    string Message,
    string? AgentVersion = null)
{
    public bool IsHealthy => State == AgentHealthState.Healthy;
}

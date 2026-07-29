using System.Text.Json.Serialization;

namespace AiLiveEdge.Desktop.Models;

public sealed record DistributionVersionInfo(
    [property: JsonPropertyName("desktopVersion")] string? DesktopVersion,
    [property: JsonPropertyName("agentVersion")] string? AgentVersion,
    [property: JsonPropertyName("buildTime")] string? BuildTime,
    [property: JsonPropertyName("architecture")] string? Architecture);

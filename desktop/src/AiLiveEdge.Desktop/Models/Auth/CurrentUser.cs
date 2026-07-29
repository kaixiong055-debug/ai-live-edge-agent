namespace AiLiveEdge.Desktop.Models.Auth;

public sealed record CurrentUser(long Id, string? Username, string? Nickname, string? Avatar);

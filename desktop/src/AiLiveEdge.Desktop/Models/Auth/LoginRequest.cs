namespace AiLiveEdge.Desktop.Models.Auth;

public sealed record LoginRequest(
    string Username,
    string Password,
    bool RememberLogin);

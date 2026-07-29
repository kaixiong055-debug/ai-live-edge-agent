namespace AiLiveEdge.Desktop.Services.Http;

public sealed class ApiException : Exception
{
    public ApiException(int? code, string? serverMessage, HttpStatusCode? statusCode)
        : base(ToFriendlyMessage(code, serverMessage, statusCode))
    {
        Code = code;
        ServerMessage = serverMessage;
        StatusCode = statusCode;
    }

    public int? Code { get; }

    public string? ServerMessage { get; }

    public HttpStatusCode? StatusCode { get; }

    public bool IsSessionExpired => StatusCode == HttpStatusCode.Unauthorized
                                    || Code is 1015010010 or 1015010011;

    public bool IsDeviceOrLicenseInvalid => Code is 1015010003 or 1015010005 or 1015010006 or 1015010007;

    public static string ToFriendlyMessage(int? code, string? serverMessage, HttpStatusCode? statusCode)
    {
        return code switch
        {
            1015010000 => "账号或密码错误",
            1015010001 => "账号已被禁用",
            1015010002 => "账号没有 Agent 登录权限",
            1015010003 => "当前租户已被禁用",
            1015010004 => "当前租户尚未开通 AI 伴播授权",
            1015010005 => "AI 伴播授权当前不可用",
            1015010006 => "AI 伴播授权已到期",
            1015010007 => "当前设备已被禁用",
            1015010008 => "设备数量已达到授权上限",
            1015010009 => "当前在线 Agent 数量已达到上限",
            1015010010 => "登录状态已失效，请重新登录",
            1015010011 => "登录状态已失效，请重新登录",
            1015010012 => "当前客户端版本不受支持",
            _ when statusCode == HttpStatusCode.Unauthorized => "登录状态已失效，请重新登录",
            _ => string.IsNullOrWhiteSpace(serverMessage) ? "登录失败，请稍后重试。" : serverMessage
        };
    }
}

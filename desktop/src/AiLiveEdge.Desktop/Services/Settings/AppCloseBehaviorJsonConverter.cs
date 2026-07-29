namespace AiLiveEdge.Desktop.Services.Settings;

public sealed class AppCloseBehaviorJsonConverter : JsonConverter<AppCloseBehavior>
{
    public override AppCloseBehavior Read(
        ref Utf8JsonReader reader,
        Type typeToConvert,
        JsonSerializerOptions options)
    {
        var value = reader.GetString();
        return string.Equals(value, "EXIT_APPLICATION", StringComparison.OrdinalIgnoreCase)
            || string.Equals(value, nameof(AppCloseBehavior.ExitApplication), StringComparison.OrdinalIgnoreCase)
            ? AppCloseBehavior.ExitApplication
            : AppCloseBehavior.MinimizeToTray;
    }

    public override void Write(
        Utf8JsonWriter writer,
        AppCloseBehavior value,
        JsonSerializerOptions options)
    {
        writer.WriteStringValue(value == AppCloseBehavior.ExitApplication
            ? "EXIT_APPLICATION"
            : "MINIMIZE_TO_TRAY");
    }
}

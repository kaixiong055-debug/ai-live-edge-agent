using System.Runtime.InteropServices;

namespace AiLiveEdge.Desktop.Services.Storage;

public sealed class WindowsDpapiSecureStorage : ISecureStorage
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("AI Live Edge Agent Session v1");

    public async Task SaveAsync<T>(string path, T value, CancellationToken cancellationToken = default)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(value);
        var protectedBytes = Dpapi.Protect(json, Entropy);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        await File.WriteAllBytesAsync(path, protectedBytes, cancellationToken);
    }

    public async Task<T?> LoadAsync<T>(string path, CancellationToken cancellationToken = default)
    {
        if (!File.Exists(path))
        {
            return default;
        }

        try
        {
            var protectedBytes = await File.ReadAllBytesAsync(path, cancellationToken);
            var json = Dpapi.Unprotect(protectedBytes, Entropy);
            return JsonSerializer.Deserialize<T>(json);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or InvalidOperationException)
        {
            DesktopLogger.Error("Secure session decrypt failed; deleting corrupted session.", ex);
            await DeleteAsync(path, cancellationToken);
            return default;
        }
    }

    public Task DeleteAsync(string path, CancellationToken cancellationToken = default)
    {
        if (File.Exists(path))
        {
            File.Delete(path);
        }
        return Task.CompletedTask;
    }

    private static class Dpapi
    {
        private const int CryptProtectUiForbidden = 0x1;

        public static byte[] Protect(byte[] data, byte[] entropy) =>
            Transform(data, entropy, protect: true);

        public static byte[] Unprotect(byte[] data, byte[] entropy) =>
            Transform(data, entropy, protect: false);

        private static byte[] Transform(byte[] data, byte[] entropy, bool protect)
        {
            var input = DataBlob.FromManaged(data);
            var optionalEntropy = DataBlob.FromManaged(entropy);
            var output = new DataBlob();
            try
            {
                var success = protect
                    ? CryptProtectData(ref input, null, ref optionalEntropy, IntPtr.Zero, IntPtr.Zero,
                        CryptProtectUiForbidden, ref output)
                    : CryptUnprotectData(ref input, IntPtr.Zero, ref optionalEntropy, IntPtr.Zero, IntPtr.Zero,
                        CryptProtectUiForbidden, ref output);
                if (!success)
                {
                    throw new InvalidOperationException($"DPAPI failed with Win32 error {Marshal.GetLastWin32Error()}.");
                }
                return output.ToArray();
            }
            finally
            {
                input.FreeManaged();
                optionalEntropy.FreeManaged();
                output.FreeNative();
            }
        }

        [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CryptProtectData(
            ref DataBlob dataIn,
            string? description,
            ref DataBlob optionalEntropy,
            IntPtr reserved,
            IntPtr promptStruct,
            int flags,
            ref DataBlob dataOut);

        [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CryptUnprotectData(
            ref DataBlob dataIn,
            IntPtr description,
            ref DataBlob optionalEntropy,
            IntPtr reserved,
            IntPtr promptStruct,
            int flags,
            ref DataBlob dataOut);

        [StructLayout(LayoutKind.Sequential)]
        private struct DataBlob
        {
            public int Size;
            public IntPtr Data;

            public static DataBlob FromManaged(byte[] bytes)
            {
                var blob = new DataBlob
                {
                    Size = bytes.Length,
                    Data = Marshal.AllocHGlobal(bytes.Length)
                };
                Marshal.Copy(bytes, 0, blob.Data, bytes.Length);
                return blob;
            }

            public byte[] ToArray()
            {
                if (Size <= 0 || Data == IntPtr.Zero)
                {
                    return [];
                }
                var bytes = new byte[Size];
                Marshal.Copy(Data, bytes, 0, Size);
                return bytes;
            }

            public void FreeManaged()
            {
                if (Data == IntPtr.Zero)
                {
                    return;
                }
                Marshal.FreeHGlobal(Data);
                Data = IntPtr.Zero;
                Size = 0;
            }

            public void FreeNative()
            {
                if (Data == IntPtr.Zero)
                {
                    return;
                }
                LocalFree(Data);
                Data = IntPtr.Zero;
                Size = 0;
            }

            [DllImport("kernel32.dll", SetLastError = true)]
            private static extern IntPtr LocalFree(IntPtr handle);
        }
    }
}

package cn.ai.live.edgeagent.asr;

import cn.ai.live.edgeagent.config.AiLiveProperties;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Sherpa-ONNX Java API 的反射适配层。
 *
 * <p>项目构建不强制携带 native 依赖；运行时由 setup 脚本把固定版本 JAR 放入受控目录。
 * 这样模型或 native 缺失时，Edge Agent 仍可启动并在 Console 中显示诊断状态。</p>
 */
class SherpaOnnxRuntimeEngine implements AutoCloseable {
    private final URLClassLoader classLoader;
    private final int sampleRate;
    private final Object recognizer;
    private Object stream;
    private boolean closed;

    private final Class<?> recognizerClass;
    private final Class<?> streamClass;

    SherpaOnnxRuntimeEngine(AiLiveProperties.Sherpa sherpa, Path modelRoot, Path apiJar, Path nativeJar) {
        try {
            this.sampleRate = sherpa.getSampleRate();
            this.classLoader = new URLClassLoader(new URL[] {
                    apiJar.toUri().toURL(),
                    nativeJar.toUri().toURL()
            }, SherpaOnnxRuntimeEngine.class.getClassLoader());
            this.recognizerClass = classLoader.loadClass("com.k2fsa.sherpa.onnx.OnlineRecognizer");
            this.streamClass = classLoader.loadClass("com.k2fsa.sherpa.onnx.OnlineStream");
            Object config = recognizerConfig(sherpa, modelRoot);
            this.recognizer = recognizerClass.getConstructor(config.getClass()).newInstance(config);
            this.stream = recognizerClass.getMethod("createStream").invoke(recognizer);
        } catch (Exception ex) {
            throw new IllegalStateException(rootMessage(ex), ex);
        }
    }

    synchronized RecognitionTick acceptAndDecode(float[] samples, String previousPartial) {
        ensureOpen();
        try {
            streamClass.getMethod("acceptWaveform", float[].class, int.class).invoke(stream, samples, sampleRate);
            decodeReady(stream);
            String text = readText(stream);
            boolean partialChanged = !text.isBlank() && !Objects.equals(text, previousPartial);
            boolean endpoint = (boolean) recognizerClass.getMethod("isEndpoint", streamClass).invoke(recognizer, stream);
            boolean finalResult = endpoint && !text.isBlank();
            if (endpoint) {
                recognizerClass.getMethod("reset", streamClass).invoke(recognizer, stream);
            }
            return new RecognitionTick(text, partialChanged, finalResult);
        } catch (Exception ex) {
            throw new IllegalStateException(rootMessage(ex), ex);
        }
    }

    synchronized String recognizeSamples(float[] samples, int sourceSampleRate) {
        ensureOpen();
        Object fileStream = null;
        try {
            fileStream = recognizerClass.getMethod("createStream").invoke(recognizer);
            streamClass.getMethod("acceptWaveform", float[].class, int.class).invoke(fileStream, samples, sourceSampleRate);
            streamClass.getMethod("acceptWaveform", float[].class, int.class)
                    .invoke(fileStream, new float[(int) (0.8 * sourceSampleRate)], sourceSampleRate);
            decodeReady(fileStream);
            return readText(fileStream);
        } catch (Exception ex) {
            throw new IllegalStateException(rootMessage(ex), ex);
        } finally {
            release(fileStream);
        }
    }

    private void decodeReady(Object targetStream) throws ReflectiveOperationException {
        while ((boolean) recognizerClass.getMethod("isReady", streamClass).invoke(recognizer, targetStream)) {
            recognizerClass.getMethod("decode", streamClass).invoke(recognizer, targetStream);
        }
    }

    private String readText(Object targetStream) throws ReflectiveOperationException {
        Object result = recognizerClass.getMethod("getResult", streamClass).invoke(recognizer, targetStream);
        Object text = result.getClass().getMethod("getText").invoke(result);
        return text == null ? "" : text.toString().trim();
    }

    private Object recognizerConfig(AiLiveProperties.Sherpa sherpa, Path modelRoot) throws ReflectiveOperationException {
        Object paraformer = builder("com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig")
                .call("setEncoder", String.class, modelRoot.resolve(sherpa.getEncoder()).toString())
                .call("setDecoder", String.class, modelRoot.resolve(sherpa.getDecoder()).toString())
                .build();

        Object model = builder("com.k2fsa.sherpa.onnx.OnlineModelConfig")
                .call("setParaformer", paraformer.getClass(), paraformer)
                .call("setTokens", String.class, modelRoot.resolve(sherpa.getTokens()).toString())
                .call("setNumThreads", int.class, sherpa.getNumThreads())
                .call("setDebug", boolean.class, sherpa.isDebug())
                .build();

        ReflectiveBuilder recognizer = builder("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
                .call("setOnlineModelConfig", model.getClass(), model)
                .call("setDecodingMethod", String.class, sherpa.getDecodingMethod())
                .call("setMaxActivePaths", int.class, sherpa.getMaxActivePaths())
                .call("setEnableEndpoint", boolean.class, sherpa.isEnableEndpoint());
        if (sherpa.isEnableEndpoint()) {
            recognizer.call("setEndpointConfig", classLoader.loadClass("com.k2fsa.sherpa.onnx.EndpointConfig"),
                    endpointConfig(sherpa));
        }
        return recognizer.build();
    }

    private Object endpointConfig(AiLiveProperties.Sherpa sherpa) throws ReflectiveOperationException {
        Object rule1 = endpointRule(false, (float) sherpa.getRule1MinTrailingSilence(), 0f);
        Object rule2 = endpointRule(true, (float) sherpa.getRule2MinTrailingSilence(), 0f);
        Object rule3 = endpointRule(false, 0f, (float) sherpa.getRule3MinUtteranceLength());
        return builder("com.k2fsa.sherpa.onnx.EndpointConfig")
                .call("setRule1", rule1.getClass(), rule1)
                .call("setRule2", rule2.getClass(), rule2)
                .call("setRule3", rule3.getClass(), rule3)
                .build();
    }

    private Object endpointRule(boolean mustContainNonSilence, float minTrailingSilence,
                                float minUtteranceLength) throws ReflectiveOperationException {
        return builder("com.k2fsa.sherpa.onnx.EndpointRule")
                .call("setMustContainNonSilence", boolean.class, mustContainNonSilence)
                .call("setMinTrailingSilence", float.class, minTrailingSilence)
                .call("setMinUtteranceLength", float.class, minUtteranceLength)
                .build();
    }

    private ReflectiveBuilder builder(String className) throws ReflectiveOperationException {
        Class<?> type = classLoader.loadClass(className);
        return new ReflectiveBuilder(type.getMethod("builder").invoke(null));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        release(stream);
        stream = null;
        release(recognizer);
        try {
            classLoader.close();
        } catch (Exception ignored) {
            // 关闭 ClassLoader 失败不影响应用退出。
        }
    }

    private void release(Object target) {
        if (target == null) {
            return;
        }
        try {
            target.getClass().getMethod("release").invoke(target);
        } catch (Exception ignored) {
            // Sherpa native 对象释放失败时 JVM 即将关闭，这里只避免二次异常覆盖主流程。
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Sherpa-ONNX engine is closed");
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    record RecognitionTick(String text, boolean partialChanged, boolean finalResult) {
    }

    private static class ReflectiveBuilder {
        private Object delegate;

        ReflectiveBuilder(Object delegate) {
            this.delegate = delegate;
        }

        ReflectiveBuilder call(String method, Class<?> parameterType, Object value) throws ReflectiveOperationException {
            delegate = delegate.getClass().getMethod(method, parameterType).invoke(delegate, value);
            return this;
        }

        Object build() throws ReflectiveOperationException {
            return delegate.getClass().getMethod("build").invoke(delegate);
        }
    }
}

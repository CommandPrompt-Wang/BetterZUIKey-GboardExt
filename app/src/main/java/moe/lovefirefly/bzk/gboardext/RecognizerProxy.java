package moe.lovefirefly.bzk.gboardext;

import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 我们自己实现的识别器（{@code klu} = {@code VoiceImeRecognizer}）——
 * 用动态代理，方法**按形状分派**（名字是混淆的、每版都变）。
 *
 * <p>实测拿到的接口形状（18.3.1）：
 * <pre>
 *   a()Lklt;                    0 参、返回枚举 ⇒ 档位（固定回 NEW_S3，UI 表现与今天一致）
 *   j(Lkma;Lkkq;Lkls;)V         3 参、第 3 个是回调接口 ⇒ 开始识别
 *   k([BLkma;Lkls;)V            同上（带注入音频，Gboard 目前不传）
 *   f(Lrwz;)V                   1 参（枚举）⇒ stopListening
 *   g()V / d()V                 0 参 void ⇒ 取消 / 销毁
 * </pre>
 * {@code g()} 与 {@code d()} 无法从签名区分，但**两者都当"收尾"处理是安全的**
 * （会话收尾本身幂等），所以不赌混淆名。
 */
final class RecognizerProxy implements InvocationHandler {

    private static final String TAG = "GboardExt";

    private final Object kltNewS3;

    private RecognizerProxy(Object kltNewS3) {
        this.kltNewS3 = kltNewS3;
    }

    static Object create(Class<?> klu, Class<?> klt) {
        return Proxy.newProxyInstance(klu.getClassLoader(), new Class<?>[] { klu },
                new RecognizerProxy(enumValue(klt, "NEW_S3")));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        try {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "VoiceEngineProxy(" + VoiceEngineHost.TAG + ")";
                }
            }
            final int n = method.getParameterCount();
            if (n == 0 && method.getReturnType().isEnum()) {
                return kltNewS3;                       // a()：档位
            }
            if (n == 0) {
                // g()/d() 无法从签名区分（0 参 void）。第一版直接"立即收尾"，实测会把
                // 还没发出去的最终结果一起掐掉 ⇒ 改成**优雅停止**（给脚本收尾 + 等结果的窗口）。
                if (VoiceEngineHost.DEV_TRACE) Log.i(TAG, "voice: 0 参接口方法 " + method.getName());
                VoiceEngineHost.stopSession("iface:" + method.getName());
                return null;
            }
            if (n == 3) {
                VoiceEngineHost.startSession(args[0], args[2]);   // j()/k()：开始
                return null;
            }
            if (n == 1) {
                VoiceEngineHost.stopSession(String.valueOf(args[0]));  // f(rwz)：停
                return null;
            }
            if (VoiceEngineHost.DEV_TRACE) {
                Log.i(TAG, "voice: 未处理的接口方法 " + method.getName() + " 参数数=" + n);
            }
        } catch (Throwable tr) {
            Log.w(TAG, "voice: proxy 调用失败 " + method.getName() + ": " + tr);
        }
        return null;
    }

    /** 取枚举常量（按名字，取不到就退第一个）。 */
    private static Object enumValue(Class<?> klt, String name) {
        if (klt == null) return null;
        final Object[] cs = klt.getEnumConstants();
        if (cs == null || cs.length == 0) return null;
        for (Object c : cs) {
            if (name.equals(String.valueOf(c))) return c;
        }
        Log.w(TAG, "voice: 枚举里没有 " + name + "，退用 " + cs[0]);
        return cs[0];
    }
}

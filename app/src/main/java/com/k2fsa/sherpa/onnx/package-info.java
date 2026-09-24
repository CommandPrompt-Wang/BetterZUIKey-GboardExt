/**
 * sherpa-onnx 官方 <b>Java API</b>（原样 vendor，Apache-2.0）。
 *
 * <p><b>来源（务必对齐版本！）</b>：仓库 {@code k2-fsa/sherpa-onnx} 的
 * <b>tag {@code v1.13.8}</b>，路径 {@code sherpa-onnx/java-api/src/main/java/com/k2fsa/sherpa/onnx/}。
 * 对应 {@code app/src/main/jniLibs/arm64-v8a/} 里那两个 .so（同一个 v1.13.8 的 AAR 里取的）。
 *
 * <p><b>踩过的坑（真机 SIGABRT + Gboard 进程被杀）</b>：一开始图省事从 <b>master</b> 拉的源码，
 * 结果 master 的 {@code OfflineRecognizerResult} 构造比 v1.13.8 的 JNI 库多一个 {@code int[] words}
 * 参数 —— JNI 里 {@code FindMethod} 找不到那个签名，在"已有 pending exception"的情况下 abort：
 * <pre>
 * java.lang.NoSuchMethodError: no non-static method
 *   "Lcom/k2fsa/sherpa/onnx/OfflineRecognizerResult;.&lt;init&gt;(Ljava/lang/String;[Ljava/lang/String;
 *    [FLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[F)V"
 * </pre>
 * ⇒ **升级 .so 时必须同时把这份源码换成同一个 tag**。
 *
 * <p><b>为什么不用 AAR 里的 Kotlin 类</b>：那些类里写死了
 * {@code System.loadLibrary("sherpa-onnx-jni")}，而库在**模块的** APK 里、Gboard 的 native
 * 搜索路径里没有它（见 {@link moe.lovefirefly.bzk.gboardext.NativeLibs}）。Java API 提供
 * {@code LibraryLoader.setAutoLoadEnabled(false)}，可以关掉自动加载、由我们自己
 * {@code System.load} 绝对路径。
 */
package com.k2fsa.sherpa.onnx;

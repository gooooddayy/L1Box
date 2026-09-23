package com.github.catvod.crawler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * jar 自杀调用中和器（设备无关的第四层防线）：
 * 重写下载的 spider jar 内 dex 的 method_ids 表，把指向
 * System.exit / Runtime.exit / Runtime.halt / Process.killProcess / Process.sendSignal
 * 的条目 name_idx 改指到**字符串表里已存在的合法方法名**（优先 toString/hashCode/equals，
 * 它们的签名与自杀方法都不匹配）。运行时方法解析按"类+名字+签名"进行，必然失败，
 * 抛 NoSuchMethodError（普通 Error，可被上层 catch(Throwable) 吞掉），而不是真的杀进程。
 *
 * ⚠ 不能改指类描述符字符串（如 'Landroid/os/Process;'）：ART 的 dex verifier 在**加载时**
 * 就校验方法名合法性，非法名 → 整个 dex 被拒 → ClassNotFoundException → 该 jar 永久不可用
 * （2026-09-13 真机实证，ai 版日志 13 次）。旧版坏 jar 会被 isLegacyBrokenAlias 识别并就地修复。
 *
 * 只改 method_ids 索引、不改任何代码字节，重算 SHA-1/Adler32 后 dex 依然合法。
 * 不依赖 SecurityManager（Android 14+ 已被系统禁用），全部 ROM 通用。
 */
public class JarKillNeutralizer {

    /**
     * 已处理过的 jar 指纹（路径 + 大小 + 修改时间），**不是**单纯的路径。
     *
     * 旧版只按路径记账：同一个路径被换版（下拉刷新下载新 jar 覆盖缓存）之后会**跳过 patch**，
     * 新 jar 里的自杀调用就没人中和了。带上大小/时间后换版必然重新处理；
     * 而 patch 内部有 changed 判断（无改动不写回），所以不会反复重写：
     * 第一次处理完把最终指纹记下，之后直接跳过。
     */
    private static final Set<String> patchedPaths = new HashSet<>();

    /** 处理键：内容一换（大小或修改时间变）就不再算"处理过" */
    private static String patchedKey(File jarFile) {
        return jarFile.getAbsolutePath() + "|" + jarFile.length() + "|" + jarFile.lastModified();
    }

    /** 就地中和 jar 内自杀调用；重复调用幂等；任何异常回退为使用原文件 */
    public static void patch(File jarFile) {
        if (jarFile == null || !jarFile.exists() || jarFile.length() <= 0) return;
        String path = jarFile.getAbsolutePath();
        synchronized (patchedPaths) {
            if (patchedPaths.contains(patchedKey(jarFile))) return;
            File tmp = null;
            try {
                // 存量坏 jar 被 setReadOnly 过；修复重写需要临时恢复可写（写完由调用方重新 setReadOnly）
                if (!jarFile.canWrite()) {
                    try { jarFile.setWritable(true); } catch (Exception ignored) { }
                }
                tmp = new File(path + ".killpatch");
                boolean changed = rewriteJar(jarFile, tmp);
                if (changed) {
                    FileInputStream in = new FileInputStream(tmp);
                    FileOutputStream out = new FileOutputStream(jarFile);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    try { in.close(); out.close(); } catch (Exception ignored) { }
                }
                patchedPaths.add(patchedKey(jarFile)); // 写回之后再取键：反映最终落盘的文件
                if (changed)
                    System.out.println("JarKillNeutralizer: 已中和 jar 自杀调用 " + jarFile.getName());
            } catch (Throwable th) {
                th.printStackTrace();
                patchedPaths.add(patchedKey(jarFile));
            } finally {
                if (tmp != null && tmp.exists())
                    try { tmp.delete(); } catch (Exception ignored) { }
            }
        }
    }

    /** 重写 zip：仅当有 dex 被修改时返回 true 并产出 tmp；否则不动 */
    private static boolean rewriteJar(File src, File tmp) throws Exception {
        InputStream is = new FileInputStream(src);
        ZipInputStream zis = new ZipInputStream(is);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        boolean changed = false;
        try {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream eb = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) eb.write(buf, 0, n);
                byte[] data = eb.toByteArray();
                String name = e.getName();
                if (name.endsWith(".dex") && data.length > 112) {
                    byte[] patched = patchDex(data);
                    if (patched != null) {
                        data = patched;
                        changed = true;
                    }
                }
                zos.putNextEntry(new ZipEntry(name));
                zos.write(data);
                zos.closeEntry();
            }
        } finally {
            try { zis.close(); } catch (Exception ignored) { }
            try { zos.close(); } catch (Exception ignored) { }
        }
        if (!changed) return false;
        FileOutputStream fo = new FileOutputStream(tmp);
        fo.write(bos.toByteArray());
        try { fo.close(); } catch (Exception ignored) { }
        return true;
    }

    /** 修改单个 dex 的 method_ids；无自杀调用返回 null */
    private static byte[] patchDex(byte[] d) throws Exception {
        int stringIdsSize = u32(d, 56);
        int stringIdsOff = u32(d, 60);
        int typeIdsSize = u32(d, 64);
        int typeIdsOff = u32(d, 68);
        int methodIdsSize = u32(d, 88);
        int methodIdsOff = u32(d, 92);
        boolean changed = false;
        // 先挑好替身名（合法方法名，字符串表里已存在），全 dex 共用一个
        int aliasIdx = legalMethodNameIdx(d, stringIdsSize, stringIdsOff);
        for (int i = 0; i < methodIdsSize; i++) {
            int off = methodIdsOff + i * 8;
            int classIdx = u16(d, off);
            int nameIdx = u32(d, off + 4);
            if (classIdx >= typeIdsSize || nameIdx >= stringIdsSize) continue;
            int descIdx = u32(d, typeIdsOff + classIdx * 4);
            if (descIdx >= stringIdsSize) continue;
            String cls = str(d, stringIdsOff, descIdx);
            if (!isKillClass(cls)) continue;
            String name = str(d, stringIdsOff, nameIdx);
            boolean kill = isKillName(name);
            // 旧版缺陷修复：name_idx 曾被改指类描述符（'Landroid/os/Process;'），verifier 加载即拒整个 dex
            boolean legacyBroken = isLegacyBrokenAlias(name);
            if (!kill && !legacyBroken) continue;
            if (aliasIdx < 0) continue; // 找不到合法替身名则不动（宁可不中和，也不能让 dex 被拒）
            putU32(d, off + 4, aliasIdx);
            changed = true;
        }
        if (!changed) return null;
        // 重算 SHA-1(偏移32到结尾) 写回偏移12；Adler32(偏移12到结尾) 写回偏移8
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(d, 32, d.length - 32);
        byte[] sig = sha.digest();
        System.arraycopy(sig, 0, d, 12, 20);
        Adler32 adler = new Adler32();
        adler.update(d, 12, d.length - 12);
        putU32(d, 8, (int) adler.getValue());
        return d;
    }

    private static boolean isKillClass(String cls) {
        return "Ljava/lang/System;".equals(cls)
                || "Ljava/lang/Runtime;".equals(cls)
                || "Landroid/os/Process;".equals(cls);
    }

    private static boolean isKillName(String name) {
        return "exit".equals(name) || "halt".equals(name)
                || "killProcess".equals(name) || "sendSignal".equals(name);
    }

    /** 旧版缺陷的指纹：name_idx 被改指类描述符（含 '/' 或 ';' 的字符串不可能是合法方法名） */
    private static boolean isLegacyBrokenAlias(String name) {
        return name != null && (name.indexOf('/') >= 0 || name.indexOf(';') >= 0);
    }

    /**
     * 在字符串表里挑一个合法方法名做替身：verifier 放行，运行时按自杀方法的签名解析必失败
     * → NoSuchMethodError。优先 toString/hashCode/equals（签名 ()Ljava/lang/String; / ()I /
     * (Ljava/lang/Object;)Z，与 exit(I)V 等全不匹配）；找不到再退回任意合法标识符，
     * 但必须排除自杀方法名本身（否则等于没中和）。
     */
    private static int legalMethodNameIdx(byte[] d, int stringIdsSize, int stringIdsOff) {
        String[] prefer = {"toString", "hashCode", "equals"};
        int generic = -1;
        for (int i = 0; i < stringIdsSize; i++) {
            String s = str(d, stringIdsOff, i);
            for (String want : prefer) {
                if (want.equals(s)) return i;
            }
            if (generic < 0 && !isKillName(s) && isLegalMethodName(s)) generic = i;
        }
        return generic;
    }

    /** 合法方法名：字母/_/$ 开头的标识符（verifier 拒绝的是含 '/' ';' '[' 等的名字） */
    private static boolean isLegalMethodName(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9' && i > 0) || c == '_' || c == '$';
            if (!ok) return false;
        }
        return true;
    }

    private static int u32(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8) | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    private static int u16(byte[] d, int off) {
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static void putU32(byte[] d, int off, int v) {
        d[off] = (byte) v;
        d[off + 1] = (byte) (v >> 8);
        d[off + 2] = (byte) (v >> 16);
        d[off + 3] = (byte) (v >> 24);
    }

    /** 读 string_ids[idx] 指向的 MUTF8 字符串（描述符均为 ASCII，按 UTF-8 读不影响判断） */
    private static String str(byte[] d, int stringIdsOff, int idx) {
        try {
            int dataOff = u32(d, stringIdsOff + idx * 4);
            // 跳过 uleb128 长度前缀
            int p = dataOff;
            int shift = 0, len = 0, b;
            do {
                b = d[p++] & 0xFF;
                len |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            int end = p;
            while (d[end] != 0) end++;
            return new String(d, p, end - p, "UTF-8");
        } catch (Throwable th) {
            return "";
        }
    }
}

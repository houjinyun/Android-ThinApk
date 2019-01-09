package com.hm.iou.thinapk.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
/**
 * 优化处理在多模块开发中，由ButterKnife产生的R2类，R2类最后都是可以直接删除掉的
 */
class R2ClassUtil {

    static Set<String> mR2JarFiles = new HashSet<>()

    /**
     * 清理掉缓存的数据
     */
    static void clear() {
        mR2JarFiles.clear()
    }

    /**
     * 扫描Jar包里的R2文件，如果Jar包里包含R2文件，则记录下来R2文件里的信息，以及该Jar包，后续会删除该Jar包里的R2代码
     *
     * @param srcJarFile
     * @param destJarFile 最终通过Transform之后包含R2文件的Jar包文件
     */
    static void collectR2Info(File srcJarFile, File destJarFile) {
        def path = srcJarFile.getAbsolutePath()
        //TODO 可以过滤掉其他更多的 jar 包
        if (path.contains("com.android.support") || path.contains("com.squareup")) {
            return
        }
        println "开始收集Jar包里的R2信息：${path}"
        boolean hasR2File = false
        JarFile jarFile = new JarFile(srcJarFile)
        jarFile.entries().grep { JarEntry entry ->
            return isR2Class(entry.name)
        }.each { JarEntry entry ->
            hasR2File = true
            jarFile.getInputStream(entry).withStream {InputStream inputStream ->
                ClassReader classReader = new ClassReader(inputStream)
                ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5) {
                    @Override
                    FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                        return super.visitField(access, name, desc, signature, value)
                    }
                }
                classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)
            }
        }
        jarFile.close()
        if (hasR2File) {
            mR2JarFiles.add(destJarFile.getAbsolutePath())
        }
    }

    /**
     * 删除Jar包里的R2信息
     */
    static void replaceAndDeleteR2Info() {
        if (mR2JarFiles.isEmpty()) {
            return
        }
        println "开始替换删除R2信息"
        for (String path : mR2JarFiles) {
            File srcJar = new File(path)
            File newJar = new File(srcJar.getParentFile(), srcJar.name + ".tmp")
            JarFile jarFile = new JarFile(srcJar)

            new JarOutputStream(new FileOutputStream(newJar)).withStream { JarOutputStream jarOutputStream ->
                jarFile.entries().each { JarEntry entry ->
                        jarFile.getInputStream(entry).withStream { InputStream inputStream ->
                            ZipEntry zipEntry = new ZipEntry(entry.name)
                            byte[] bytes = inputStream.bytes
                            if (isR2Class(entry.name)) {
                                //如果是R2类，则直接删除
                                bytes = null
                            } else {
                                //不是R2类，则不做处理
                            }
                            if (bytes != null) {
                                jarOutputStream.putNextEntry(zipEntry)
                                jarOutputStream.write(bytes)
                                jarOutputStream.closeEntry()
                            }
                        }
                    }
            }

            jarFile.close()
            srcJar.delete()
            newJar.renameTo(srcJar)
        }
    }

    static boolean isR2Class(String name) {
        name ==~ '''.*/R2\\$.*\\.class|.*/R2\\.class'''
    }

    static boolean isR2File(String name) {
        name ==~ '''.*R2\\$.*\\.class|.*R2\\.class'''
    }
}
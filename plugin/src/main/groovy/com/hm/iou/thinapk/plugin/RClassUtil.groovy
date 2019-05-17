package com.hm.iou.thinapk.plugin

import org.objectweb.asm.*

import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
/**
 * 优化处理R文件
 */
class RClassUtil {

    /**
     * 收集所有 R.class 及其内部类里的 int 常量字段信息
     * 存储的 key = class全路径类名 + 字段名，value = 该字段的常量值
     */
    static Map<String, Integer> mRInfoMap = new HashMap<>()

    /**
     * 清理掉缓存的数据
     */
    static void clear() {
        mRInfoMap.clear()
    }

    /**
     * 收集R类相关信息，将所有 R.class 类里的 int 常量值缓存起来
     *
     * @param file class文件
     */
    static void collectRInfo(File file) {
        if (!isRClass(file.absolutePath)) {
            return
        }
        def fullClassName = getFullClassName(file.getAbsolutePath())
        println "需要收集的R类信息：fullClassName = ${fullClassName}"
        new FileInputStream(file).withStream { InputStream is ->
            ClassReader classReader = new ClassReader(is)
            ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5) {
                @Override
                FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    if (value instanceof Integer) {
                        //遍历读取所有 R.class 里的 int 常量值，例如 com/hm/library1/R$mipmap.class 里的 ic_launcher 常量值，
                        //存储时存为 "com/hm/library1/R$mipmapic_launcher" = ***
                        mRInfoMap.put(fullClassName - ".class" + name, value)
                    }
                    return super.visitField(access, name, desc, signature, value)
                }
            }
            classReader.accept(classVisitor, 0)
        }
    }

    /**
     * 删除单个class文件里的 R 信息，主要处理逻辑如下：
     * 1. 如果是 R.class 的内部类，R$styleable.class除外，直接删除里面的 int 常量值，保留 keep 住的常量值；
     * 2. 如果是 R$styleable.class，则删除里面的 int 常量值，保留 int[] 常量值；
     * 3. 如果是非 R.class文件，则将里面所有对 R.class 引用 int 常量的地方，直接替换成 int 数值；
     *
     * @param classFile
     */
    static void replaceAndDeleteRInfo(File classFile, ThinApkRExtension extension) {
        def fullClassName = getFullClassName(classFile.getAbsolutePath())
        //如果是除了R&styleable.class的除外
        if (isRFileExceptStyleable(classFile.getAbsolutePath())) {
            ThinApkRExtension.KeepRInfo keepRInfo = extension.shouldKeepRFile(fullClassName)
            if (keepRInfo != null) {
                println "有字段需要keep的R文件：${classFile.getAbsolutePath()}"

                new FileInputStream(classFile).withStream { InputStream is ->
                    ClassReader classReader = new ClassReader(is.bytes)
                    ClassWriter classWriter = new ClassWriter(0)
                    ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5, classWriter) {
                        @Override
                        FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                            if (value instanceof Integer) {
                                if (keepRInfo.shouldKeep(name)) {
                                    println "keep了字段： ${name}"
                                    return super.visitField(access, name, desc, signature, value)
                                }
                                //不需要 keep 的字段，直接删除该 int 常量值
                                return null
                            }
                            return super.visitField(access, name, desc, signature, value)
                        }
                    }
                    classReader.accept(classVisitor, 0)

                    byte[] bytes = classWriter.toByteArray()
                    def newClassFile = new File(classFile.getParentFile(), classFile.name + ".tmp")
                    new FileOutputStream(newClassFile).withStream { OutputStream os ->
                        os.write(bytes)
                    }

                    //重命名
                    classFile.delete()
                    newClassFile.renameTo(classFile)
                }

            } else {
                println "没有字段需要keep，直接删除该R文件：${fullClassName}"
                classFile.delete()
            }
        } else {
            if (isRClass(classFile.getAbsolutePath())) {
                //如果是 R$styleable.class 则删除里面的 static final int 类型的字段
                println "删除${fullClassName}里的 static final int 字段"

                new FileInputStream(classFile).withStream { InputStream is ->
                    ClassReader classReader = new ClassReader(is.bytes)
                    ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
                    ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5, classWriter) {
                        @Override
                        FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                            //只替换 static final int 字段
                            if (value instanceof Integer) {
                                return null
                            }
                            return super.visitField(access, name, desc, signature, value)
                        }
                    }
                    classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

                    byte[] bytes = classWriter.toByteArray()
                    def newClassFile = new File(classFile.getParentFile(), classFile.name + ".tmp")
                    new FileOutputStream(newClassFile).withStream { OutputStream os ->
                        os.write(bytes)
                    }

                    //重命名
                    classFile.delete()
                    newClassFile.renameTo(classFile)
                }
            } else {
                //如果是普通的类信息，则将引用 R 类的地方替换成 int 值
                new FileInputStream(classFile).withStream { InputStream is ->
                    byte[] bytes = replaceRInfo(is.bytes)
                    def newClassFile = new File(classFile.getParentFile(), classFile.name + ".tmp")
                    new FileOutputStream(newClassFile).withStream { OutputStream os ->
                        os.write(bytes)
                    }

                    //重命名
                    classFile.delete()
                    newClassFile.renameTo(classFile)
                }
            }
        }
    }

    /**
     * 遍历 jar 文件里的所有 class，替换所有对 R.class 的直接引用
     *
     * @param srcJar jar文件
     * @param extension
     */
    static void replaceAndDeleteRInfoFromJar(File srcJar, ThinApkRExtension extension) {
        File newJar = new File(srcJar.getParentFile(), srcJar.name + ".tmp")
        JarFile jarFile = new JarFile(srcJar)

        new JarOutputStream(new FileOutputStream(newJar)).withStream { JarOutputStream jarOutputStream ->
            jarFile.entries().each { JarEntry entry ->
                jarFile.getInputStream(entry).withStream { InputStream inputStream ->
                    ZipEntry zipEntry = new ZipEntry(entry.name)
                    byte[] bytes = inputStream.bytes
                    if (entry.name.endsWith(".class")) {
                        bytes = replaceRInfo(bytes)
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

    /**
     * 将所有对 R.class 有引用的代码，直接替换成 int 值，这样在其他类里就不会内联 R.class 了，
     * R.class 存不存在就不会影响编译运行了
     *
     * @param bytes
     * @return
     */
    private static byte[] replaceRInfo(byte[] bytes) {
        ClassReader classReader = new ClassReader(bytes)
        ClassWriter classWriter = new ClassWriter(0)
        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5, classWriter) {

            @Override
            MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                def methodVisitor = super.visitMethod(access, name, desc, signature, exceptions)
                methodVisitor = new MethodVisitor(Opcodes.ASM5, methodVisitor) {
                    @Override
                    void visitFieldInsn(int opcode, String owner, String name1, String desc1) {
                        String key = owner + name1
                        Integer value = mRInfoMap.get(key)
                        if (value != null) {
                            println "替换对R.class的直接引用：${owner} - ${name1}"
                            super.visitLdcInsn(value)
                        } else {
                            super.visitFieldInsn(opcode, owner, name1, desc1)
                        }
                    }
                }
                return methodVisitor
            }
        }
        classReader.accept(classVisitor, 0)
        return classWriter.toByteArray()
    }

    /**
     * 判断该 class 文件是否是 R.class 类，及其内部类如 R$id.class
     *
     * @param classFilePath class文件的全路径名，例如：~app/build/intermediates/classes/debug/com/hm/library1/R.class
     * @return 如果是R.class及其它内部类class则返回true，否则返回false
     */
    static boolean isRClass(String classFilePath) {
        classFilePath ==~ '''.*/R\\$.*\\.class|.*/R\\.class'''
    }

    /**
     * 判断该 class 文件是否是 R.class 类，及其内部类如 R$id.class，但是 R$styleable.class 类排除在外
     *
     * @param classFilePath
     * @return
     */
    static boolean isRFileExceptStyleable(String classFilePath) {
        classFilePath ==~ '''.*/R\\$(?!styleable).*?\\.class|.*/R\\.class'''
    }

    /**
     * 从形如 ~/HM-ThinApk/app/build/intermediates/classes/debug/com/hm/library1/R.class 的类路径中截取出 com/hm/library1/R.class
     * 根据Android Studio版本的不同，编译配置不同，路径也可能不同，例如：app/build/intermediates/javac/officialDebug/compileOfficialDebugJavaWithJavac/classes/android/arch/lifecycle/R.class
     * 不管是当前工程的代码，还是远程依赖的aar包，在打包编译时，都会在工程的 app/build/intermediates/classes 路径下生成一系列R.class文件，
     * 根据打包模式是 debug 还是 release来区分，从中可以截取出 R.class 的包名了。
     *
     * @param filePath class文件全路径
     * @return 返回类似 "com/hm/library1/R.class"、"com/hm/library1/R$mipmap.class"，其实就是类的全路径class名
     */
    static String getFullClassName(String filePath) {
        println "${filePath}"

        String mode = "/debug/"
        int index = filePath.indexOf(mode)
        if (index == -1) {
            mode = "/release/"
            index = filePath.indexOf(mode)
        }
        if (index != -1) {
            return filePath.substring(index) - "${mode}"
        }

        index = filePath.indexOf("/classes")
        filePath = filePath.substring(index) - "/classes" - "/debug" - "/release"
        filePath = filePath.substring(1)
        println filePath
        return filePath
    }

}
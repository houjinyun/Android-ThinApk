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

    static Map<String, Integer> mRInfoMap = new HashMap<>()

    /**
     * 清理掉缓存的数据
     */
    static void clear() {
        mRInfoMap.clear()
    }

    /**
     * 收集R类相关信息
     *
     * @param file
     */
    static void collectRInfo(File file) {
        if (!isRClass(file.absolutePath)) {
            return
        }
        def fullClassName = getFullClassName(file.getAbsolutePath())
        println "fullClassName = ${fullClassName}"
        new FileInputStream(file).withStream { InputStream is ->
            ClassReader classReader = new ClassReader(is)
            ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5) {
                @Override
                FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    //只替换
                    if (value instanceof Integer) {
                        mRInfoMap.put(fullClassName - ".class" + name, value)
                    }
                    return super.visitField(access, name, desc, signature, value)
                }
            }
            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)
        }
    }

    /**
     * 删除单个class文件里的 R 信息
     *
     * @param classFile
     */
    static void replaceAndDeleteRInfo(File classFile, ThinApkRExtension extension) {
        def fullClassName = getFullClassName(classFile.getAbsolutePath())
        if (isRFileExceptStyleable(classFile.getAbsolutePath())) {
            ThinApkRExtension.KeepRInfo keepRInfo = extension.shouldKeepRFile(fullClassName)
            if (keepRInfo != null) {
                println "R文件被keep住：${classFile.getAbsolutePath()}"

                new FileInputStream(classFile).withStream { InputStream is ->
                    ClassReader classReader = new ClassReader(is.bytes)
                    ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
                    ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5, classWriter) {
                        @Override
                        FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                            //只替换
                            if (value instanceof Integer) {
                                if (keepRInfo.shouldKeep(name)) {
                                    println "keep了字段 ${name}"
                                    return super.visitField(access, name, desc, signature, value)
                                }
                                return null
                            }
                            return super.visitField(access, name, desc, signature, value)
                        }
                    }
                    classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

                    byte[] bytes = classWriter.toByteArray()
                    def newClassFile = new File(classFile.getParentFile(), classFile.name + ".tmp")
                    new FileOutputStream(newClassFile).withStream {OutputStream os ->
                        os.write(bytes)
                    }

                    //重命名
                    classFile.delete()
                    newClassFile.renameTo(classFile)
                }

            } else {
                println "删除R文件：${classFile.getAbsolutePath()}"
                classFile.delete()
            }
        } else {
            if (isRClass(classFile.getAbsolutePath())) {
                //如果是 R$styleable.class 则删除里面的 static final int 类型的字段
                println "删除${classFile.getAbsolutePath()}里的 static final int 字段"

                new FileInputStream(classFile).withStream {InputStream is ->
                    ClassReader classReader = new ClassReader(is.bytes)
                    ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
                    ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM5, classWriter) {
                        @Override
                        FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                            //只替换
                            if (value instanceof Integer) {
                                return null
                            }
                            return super.visitField(access, name, desc, signature, value)
                        }
                    }
                    classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)

                    byte[] bytes = classWriter.toByteArray()
                    def newClassFile = new File(classFile.getParentFile(), classFile.name + ".tmp")
                    new FileOutputStream(newClassFile).withStream {OutputStream os ->
                        os.write(bytes)
                    }

                    //重命名
                    classFile.delete()
                    newClassFile.renameTo(classFile)
                }
            } else {
                //如果是普通的类信息，则将引用 R 类的地方替换成 int 值
                new FileInputStream(classFile).withStream {InputStream is ->
                    byte[] bytes = replaceRInfo(is.bytes)
                    def newClassFile = new File(classFile.getParentFile(), classFile.name + ".tmp")
                    new FileOutputStream(newClassFile).withStream {OutputStream os ->
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
     * 删除Jar包里的R信息
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
     * 删除类的R内联代码
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
                        value != null ? super.visitLdcInsn(value) : super.visitFieldInsn(opcode, owner, name1, desc1)
                    }
                }
                return methodVisitor
            }
        }
        classReader.accept(classVisitor, 0)
        return classWriter.toByteArray()
    }

    static boolean isRClass(String name) {
        name ==~ '''.*/R\\$.*\\.class|.*/R\\.class'''
    }

    static boolean isRFileExceptStyleable(String name) {
        name ==~ '''.*/R\\$(?!styleable).*?\\.class|.*/R\\.class'''
    }

    //从形如 /Users/hjy/Desktop/heima/code/gitlab/HM-ThinApk/app/build/intermediates/classes/debug/com/hm/library1/R.class 的类路径中截取出 com/hm/library1/R.class
    //返回类似 com/hm/library1/R.class com/hm/library1/R$mipmap.class，其实就是类的全路径class名
    static String getFullClassName(String filePath) {
        String mode = "/debug/"
        int index = filePath.indexOf(mode)
        if (index == -1) {
            mode = "/release/"
            index = filePath.indexOf(mode)
        }
        return filePath.substring(index) - "${mode}"
    }

}
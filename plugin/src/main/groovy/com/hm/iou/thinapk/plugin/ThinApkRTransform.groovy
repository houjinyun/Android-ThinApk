package com.hm.iou.thinapk.plugin

import com.android.build.api.transform.Context
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.Format
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.pipeline.TransformManager
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

/**
 * 对R文件进行瘦身
 */
class ThinApkRTransform extends Transform {

    Project project
    ThinApkRExtension extension

    ThinApkRTransform(Project project, ThinApkRExtension thinApkRExtension) {
        this.project = project
        this.extension = thinApkRExtension
    }

    @Override
    String getName() {
        return "ThinApkRTransform"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(Context context, Collection<TransformInput> inputs, Collection<TransformInput> referencedInputs, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException, TransformException, InterruptedException {
        println "----------------------------------"
        println "------ThinApkRTransform start-----"

        //先删除原来的缓存文件
        outputProvider.deleteAll()
        R2ClassUtil.clear()
        RClassUtil.clear()
        def jarList = []

        inputs.each { TransformInput input ->
            //第一次循环，只是为了收集 R.java 类信息
            input.directoryInputs.each { DirectoryInput directoryInput ->
                if (directoryInput.file.isDirectory()) {
                    directoryInput.file.eachFileRecurse { File file ->
                        if (file.isFile()) {
                            //收集R.java类的信息
                            RClassUtil.collectRInfo(file)
                        }
                    }
                } else {
                    //收集R.java类的信息
                    RClassUtil.collectRInfo(directoryInput.file)
                }
            }
        }

        inputs.each { TransformInput input ->
            //第二次循环，删除R.java类信息，以及R2.java类
            input.directoryInputs.each { DirectoryInput directoryInput ->
                if (directoryInput.file.isDirectory()) {
                    println "这是个目录：" + directoryInput.file.getAbsolutePath()

                    directoryInput.file.eachFileRecurse {File file ->
                        println "所有文件：" + file.getAbsolutePath()
                        if (file.isFile()) {
                            if (R2ClassUtil.isR2File(file.name)) {
                                if (extension.deleteR2) {
                                    //直接删除原来的R2文件
                                    println "直接删除原来的R2文件: ${file.name}"
                                    file.delete()
                                }
                            } else {
                                RClassUtil.replaceAndDeleteRInfo(file, extension)
                            }
                        }
                    }

                } else {
                    println "这不是目录，是个文件：" + directoryInput.file.getAbsolutePath()
                    //直接删除原来的R2文件
                    if (R2ClassUtil.isR2File(directoryInput.file.name)) {
                        if (extension.deleteR2) {
                            println "直接删除原来的R2文件: ${directoryInput.file.name}"
                            directoryInput.file.delete()
                        }
                    } else {
                        RClassUtil.replaceAndDeleteRInfo(directoryInput.file, extension)
                    }
                }

                def dest = outputProvider.getContentLocation(directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
                FileUtils.copyDirectory(directoryInput.file, dest)
            }


            input.jarInputs.each { JarInput jarInput ->
                println "这是Jar文件：" + jarInput.file.getAbsolutePath()

                def jarName = jarInput.name
                def md5 = DigestUtils.md5Hex(jarInput.file.getAbsolutePath())
                if (jarName.endsWith(".jar")) {
                    jarName = jarName.substring(0, jarName.length() - 4)
                }
                def dest = outputProvider.getContentLocation(jarName + md5, jarInput.contentTypes, jarInput.scopes, Format.JAR)

                def src = jarInput.file
                if (extension.deleteR2 && src.getAbsolutePath().endsWith(".jar")) {
                    R2ClassUtil.collectR2Info(jarInput.file, dest)
                }

                FileUtils.copyFile(src, dest)

                //所有的jar包
                if (src.path.contains("com.squareup")) {
                    //TODO 有很多jar其实是不需要处理的
                } else {
                    jarList.add(dest)
                }
            }
        }

        if (extension.deleteR2) {
            //删除Jar包里的R2信息
            R2ClassUtil.replaceAndDeleteR2Info()
        }

        //删除R信息
        for (File jarFile : jarList) {
            println "处理Jar包里的R信息：${jarFile.getAbsolutePath()}"
            RClassUtil.replaceAndDeleteRInfoFromJar(jarFile, extension)
        }

        extension.keepInfo.all {ThinApkRExtension.KeepRInfo info ->
            info.printInfo()
        }

        println "------ThinApkRTransform end-------"
        println "----------------------------------"
    }

}
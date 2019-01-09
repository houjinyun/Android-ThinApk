package com.hm.iou.thinapk.plugin

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ThinApkPlugin implements Plugin<Project>{

    @Override
    void apply(Project project) {
        def android = project.extensions.getByType(AppExtension)

       def thinApkRExt = project.extensions.create("thinRConfig", ThinApkRExtension, project)

        //对 最后生成的R 文件进行瘦身
        println "=========插件注册Transform=========="
        android.registerTransform(new ThinApkRTransform(project, thinApkRExt))
    }

}
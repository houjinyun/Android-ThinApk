package com.hm.iou.thinapk.plugin

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ThinApkPlugin implements Plugin<Project>{

    @Override
    void apply(Project project) {
        def android = project.extensions.getByType(AppExtension)

        project.extensions.create("thinRConfig", ThinApkRExtension, project)
        android.registerTransform(new ThinApkRTransform(project))
    }

}
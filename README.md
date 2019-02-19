###1. 前言

记得早期刚开始做 Android 开发的时候，一个 Android 应用也就几兆的大小。到现在，一个 APP少说十几兆，大则好几十兆甚至上百兆。所以针对 apk 包的瘦身问题，摆在了所有开发者的面前。毕竟安装包越小，下载安装肯定也就更快，对 APP 的运营也是有帮助的。网上已经有很多关于这方面的文章了，但是很多都泛泛而谈，道理大家都懂，但是怎么实操确不清楚。所以，本人计划将实际项目中用到的方案写出来，剖析剖析原理，一是给自己做个总结，二是给有需要的人做个参考，共同交流进步。

###2. R.java 文件结构

众所周知，R.java 是自动生成的，它包含了应用内所有资源的名称到数值的映射关系。先创建一个最简单的工程，看看 R.java 文件的内容：

![R.java文件结构](https://upload-images.jianshu.io/upload_images/5955727-556ac6afce8e0d8c.jpg?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

从图中可以看到，R.java 内部包含了很多内部类：如 layout、mipmap、drawable、string、id 等等，这些内部类里面只有 2 种数据类型的字段：

```
public static final int 
public static final int[]
```

这里面，只有 styleable 最为特殊，只有它里面有 **public static final int[]** 类型的字段定义，其它都只有 **int** 类型的字段。

```
public static final class styleable {
	...
	public static final int[] ActionBarLayout = new int[]{16842931};
	public static final int ActionBarLayout_android_layout_gravity = 0;
	...
}

```

此外，我们发现 R.java 类的代码行数有 1800 多行了，这还只是一个简单的工程，压根没有任何业务逻辑。如果我们采用组件化开发或者在工程里创建多个 module ，你会发现在每个模块的包名下都会生成一个 R.java 文件。以我的实际项目为例，我们采用组件化开发的架构，一个 APP 由将近 30 个组件组成，编译时则会生成将近 30 个 R.java 文件，算上业务逻辑里的资源 id ，平均每个 R.java 算 3000 行代码的话，则总共有 90000 行的代码，当然这只是一个很笼统的估计。

###3.为什么R文件可以删除
所有的 R.java 里定义的都是常量值，以 Activity 为例：

```
public class MainActivity extends AppCompatActivity {

	protected void onCreate(Bundle savedInstanceState) {
	    super.onCreate(savedInstanceState);
	    setContentView(R.layout.activity_main);
	}
	
}
```

R.layout.activity_main 实际上对应的是一个 int 型的常量值，那么如果我们编译打包时，将所有这些对 R 类的引用直接替换成常量值，效果也是一样的，那么 R.java 类在 apk 包里就是冗余的了。

前面说过 R.java 类里有2种数据类型，一种是 **static final int** 类型的，这种常量在运行时是不会修改的，另一种是 **static final int[]** 类型的，虽然它也是常量，但它是一个数组类型，并不能直接删除替换，所以打包进 apk 的 R 文件中，理论上除了 **static final int[]** 类型的字段，其他都可以全部删除掉。以上面这个为例：我们需要做的是编译时将 setContentView(R.layout.activity_main) 替换成：

```
setContentView(213196283);
```

###4. ProGuard对R文件的混淆
通常我们会采用 ProGuard 进行混淆，你会发现混淆也能删除很多 R$*.class，但是混淆会造成一个问题：混淆后不能通过反射来获取资源了。现在很多应用或者SDK里都有通过反射调用来获取资源，比如大家最常用的统计SDK友盟统计、友盟分享等，就要求 R 文件不能混淆掉，否则会报错，所以我们常用的做法是开启混淆，但 keep 住 R 文件，在 proguard 配置文件中增加如下配置：

```
-keep class **.R$* {
    *;
}
-dontwarn **.R$*
-dontwarn **.R
```

那么接下来，我们讲讲如何删除所有 R 文件里的冗余字段。

###4. 开发思路
具体的目标知道了，那怎么去实现呢，先说说我的思路：

1. 在打包 apk 编译时找到所有的 R$*.class ；
2. 收集所有 R$*.class 里的 public static final int 字段信息，将键值对缓存起来；
3. 遍历所有的 class，如果是 R.class，则删除里面的 public static final int 字段，但是需要保留 R$styleable.class 里的 public static final int[] 字段；
4. 如果不是 R$*.class ，则遍历该 class 里所有引用的静态字段，如果有对 R 文件里的静态字段引用，则根据前面缓存的键值对将其替换成对应的常量 int 值；

为了实现这个目标，我们需要创建一个 Gralde Plugin，在编译打包时能直接帮我们完成。这里需要用到2个技术：其一是 Gradle Transform，它能够在项目构建阶段即由 class 到 dex 转换期间，让开发者修改 class 文件；其二是 ASM 技术，它能让我们直接操作修改 class 文件。

###5.R文件瘦身插件实操

这里提取出几个主要步骤来讲讲，具体代码已经开源。

怎么判断某个 class 文件是否为 R\$*.class ，主要是通过 class 的文件名来判断，然后通过 ASM 技术来读取 R$\*.class 里的所有 int 字段：

```
    /**
     * 收集所有 R.class 及其内部类里的 int 常量字段信息
     * 存储的 key = class全路径类名 + 字段名，value = 该字段的常量值
     */
    static Map<String, Integer> mRInfoMap = new HashMap<>()

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
     * 判断该 class 文件是否是 R.class 类，及其内部类如 R$id.class
     *
     * @param classFilePath class文件的全路径名，例如：/Users/hjy/Desktop/app/build/intermediates/classes/debug/com/hm/library1/R.class
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
     * 从形如 /Users/hjy/Desktop/heima/code/gitlab/HM-ThinApk/app/build/intermediates/classes/debug/com/hm/library1/R.class 的类路径中截取出 com/hm/library1/R.class
     * 不管是当前工程的代码，还是远程依赖的aar包，在打包编译时，都会在工程的 app/build/intermediates/classes 路径下生成一系列R.class文件，
     * 根据打包模式是 debug 还是 release来区分，从中可以截取出 R.class 的包名了。
     *
     * @param filePath class文件全路径
     * @return 返回类似 "com/hm/library1/R.class"、"com/hm/library1/R$mipmap.class"，其实就是类的全路径class名
     */
    static String getFullClassName(String filePath) {
        String mode = "/debug/"
        int index = filePath.indexOf(mode)
        if (index == -1) {
            mode = "/release/"
            index = filePath.indexOf(mode)
        }
        return filePath.substring(index) - "${mode}"
    }
    
```


在 Android 的 Transform 阶段，我们能读取到所有的 class 文件和 jar 包，那么 R$\*.class 是在文件目录里还是在 jar 包里呢？Android Studio编译时，会重新生成所有不同包名下的 R$\*.class，所有我们不需要遍历 jar 包来查找 R$*.class 文件，只需要遍历 class 文件即可，Transform类里的大致代码如下：

```
   @Override
    void transform(Context context, Collection<TransformInput> inputs, Collection<TransformInput> referencedInputs, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException, TransformException, InterruptedException {
        inputs.each { TransformInput input ->
            //第一次循环，只是为了收集 R.java 类信息
            input.directoryInputs.each { DirectoryInput directoryInput ->
                if (directoryInput.file.isDirectory()) {
                    directoryInput.file.eachFileRecurse { File file ->
                        if (file.isFile()) {
                            //收集R.java类的信息
                            collectRInfo(file)
                        }
                    }
                } else {
                    //收集R.java类的信息
                    collectRInfo(directoryInput.file)
                }
            }

        }
        ......
    }
```

通过这种方式可以收集到所有 R.class 文件，接下来我们需要二次遍历所有的 class 文件和 jar 包，这次需要删除 R.class 以及替换对 R.class 的直接引用。

```
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
```

删除 jar 包中的 R.class 相关引用：

```
    /**
     * 遍历 jar 文件里的所有 class，替换所有对 R.class 的直接引用
     *
     * @param srcJar jar文件
     */
    static void replaceAndDeleteRInfoFromJar(File srcJar) {
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
```

这样还是会有个弊端，如果删除了所有的 R$*.class 里的字段，某些资源通过反射调用依旧会失败，所以我们还是需要能通过配置来 keep 住某些字段。

###6.资源keep
所有的代码已经开源，有兴趣的可以查看，里面会有更具体的注释：[Android R文件瘦身插件：源码地址](https://github.com/houjinyun/Android-ThinApk)

资源 keep 配置示例：

```
thinRConfig {
    keepInfo {
        demomipmap {
            keepRPackageName = "com.hm.iou.thinapk.demo"
            keepRClassName = "mipmap"
            keepResName = ["ic_launcher"]
            keepResNameReg = ["ic_launcher.*"]
        }
        librarystring {
        	  keepRPackageName = "com.hm.iou.library"
            keepRClassName = "string"
            keepResName = ["app_name"]
            keepResNameReg = [""]
        }
    }
}
```
上面这个配置，com.hm.iou.thinapk.demo.R.mipmap 类里名为 ic_launcher 的字段不会被删除，com.hm.iou.library.R.string 类里名为 app_name 的字段不会被删除。

* keepRPackageName：表示 R 文件所在的包名；
* keepRClassName：表示 R 文件里的内部类名，如mipmap、string、id、drawable、layout 等等；
* keepResName：要 keep 的资源名，是个数组
* keepResNameReg：要 keep 的资源名，这是个正则表达式，会根据正则来进行匹配；

###7.小结
本插件对采用组件化方式开发方式的app，或者有大量资源id定义的app可能会有显著效果，以我自己的项目为例，采用该插件以后，apk包大小减小了差不多0.4M左右。对这2种情况除外的app，效果可能并不会那么显著。当然这种方案只是锦上添花而已，我们应用里少用几张图片，可能包大小就减小了很多。

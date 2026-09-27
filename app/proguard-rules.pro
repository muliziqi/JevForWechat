-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep public class dev.jev.wechat.ModuleMain extends io.github.libxposed.api.XposedModule {
    public <init>();
}

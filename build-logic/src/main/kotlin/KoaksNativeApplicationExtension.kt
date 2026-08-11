import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

public abstract class KoaksNativeApplicationExtension @Inject constructor(
    objects: ObjectFactory,
) {
    public val baseName: Property<String> = objects.property(String::class.java)
    public val entryPoint: Property<String> = objects.property(String::class.java)
}

package com.cryptomorin.xseries.reflection.parser;

import com.cryptomorin.xseries.reflection.Handle;
import com.cryptomorin.xseries.reflection.XReflection;
import com.cryptomorin.xseries.reflection.jvm.*;
import com.cryptomorin.xseries.reflection.jvm.classes.DynamicClassHandle;
import com.cryptomorin.xseries.reflection.minecraft.MinecraftPackage;
import org.intellij.lang.annotations.Language;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ReflectionParser {
    private final String declaration;
    private Pattern pattern;
    private Matcher matcher;
    private ReflectiveNamespace namespace;
    private Map<String, Class<?>> imports;
    private final Set<Flag> flags = EnumSet.noneOf(Flag.class);
    private final Set<String> names = new HashSet<>(5);

    public ReflectionParser(@Language("Java") String declaration) {
        this.declaration = declaration;
    }

    private enum Flag {
        PUBLIC, PROTECTED, PRIVATE, FINAL, TRANSIENT, ABSTRACT, STATIC, NATIVE, SYNCHRONIZED, STRICTFP, VOLATILE;

        @SuppressWarnings("RegExpUnnecessaryNonCapturingGroup")
        private static final String FLAGS_REGEX = "(?<flags>(?:(?:" + Arrays.stream(Flag.values())
                .map(Enum::name)
                .map(x -> x.toLowerCase(Locale.ENGLISH))
                .collect(Collectors.joining("|"))
                + ")\\s*)+)?";
    }

    @Language("RegExp")
    private static final String JAVA_IDENTIFIER_REGEX = "[A-Za-z0-9_$]+";
    @Language("RegExp")
    private static final String JAVA_TYPE_REGEX = "[A-Za-z0-9_$.]+(<[\\w<>\\[\\], ]+>)?((?:\\[])*)";

    @Language("RegExp")
    private static String id(@Language("RegExp") String groupName) {
        if (groupName == null) return JAVA_IDENTIFIER_REGEX;
        return "(?<" + groupName + '>' + JAVA_IDENTIFIER_REGEX + ')';
    }

    @Language("RegExp")
    private static String type(@Language("RegExp") String groupName) {
        if (groupName == null) return JAVA_TYPE_REGEX;
        return "(?<" + groupName + '>' + JAVA_TYPE_REGEX + ')';
    }

    private Class<?>[] parseTypes(String[] typeNames) {
        Class<?>[] classes = new Class[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            String typeName = typeNames[i];
            typeName = typeName.trim().substring(0, typeName.lastIndexOf(' ')).trim();
            classes[i] = parseType(typeName);
        }
        return classes;
    }

    private static final Map<String, Class<?>> PREDEFINED_TYPES = new HashMap<>();

    static {
        Arrays.asList(
                byte.class, short.class, int.class, long.class, float.class, double.class, boolean.class, char.class, void.class,
                Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, Boolean.class, Character.class, Void.class,
                String.class, Optional.class, StringBuilder.class, StringBuffer.class, UUID.class
        ).forEach(x -> PREDEFINED_TYPES.put(x.getSimpleName(), x));
    }

    private Class<?> parseType(String typeName) {
        if (this.imports == null && this.namespace != null) {
            this.imports = this.namespace.getImports();
        }

        String firstTypeName = typeName;
        int arrayDimension = 0;
        if (typeName.endsWith("[]")) { // Arrays
            String replaced = typeName.replace("[]", "");
            arrayDimension = (typeName.length() - replaced.length()) / 2;
            typeName = replaced;
        }
        if (typeName.endsWith(">")) { // Generic
            typeName = typeName.substring(0, typeName.indexOf('<'));
        }

        Class<?> clazz = null;
        if (!typeName.contains(".")) {
            clazz = PREDEFINED_TYPES.get(typeName);
            if (clazz == null && imports != null) clazz = this.imports.get(typeName);
        }
        if (clazz == null) {
            try {
                clazz = Class.forName(typeName);
            } catch (ClassNotFoundException ignored) {
            }
        }

        if (clazz == null) error("Unknown type '" + firstTypeName + "' -> '" + typeName + '\'');
        if (arrayDimension != 0) {
            clazz = XReflection.of(clazz).asArray(arrayDimension).unreflect();
        }
        return clazz;
    }


    @Language("RegExp")
    private static final String PACKAGE_REGEX = "(?:package\\s+(?<package>(?:" + id(null) + "|\\.)+)\\s*;\\s*)?";
    @Language("RegExp")
    private static final String CLASS_TYPES = "(?<classType>class|interface|enum)";
    @Language("RegExp")
    private static final String GENERIC = "(?:<" + id(null) + ">)*";
    private static final Pattern CLASS = Pattern.compile(PACKAGE_REGEX + Flag.FLAGS_REGEX + CLASS_TYPES + "\\s+" + id("className") +
            "(?:\\s+extends\\s+(?<superclasses>[\\w.$]+))?\\s+(implements\\s+(?<interfaces>[\\w.$]+))?(?:\\s*\\{\\s*})?\\s*");
    private static final Pattern METHOD = Pattern.compile(Flag.FLAGS_REGEX + type("methodReturnType") + "\\s+"
            + id("methodName") + "\\s*\\(\\s*(?<parameters>[\\w$_,. ]+)?\\s*\\)\\s*;\\s*?");
    private static final Pattern FIELD = Pattern.compile(Flag.FLAGS_REGEX + id("fieldType") + "\\s+" + id("fieldName") + "\\s*;\\s*?");

    public ReflectionParser imports(ReflectiveNamespace namespace) {
        this.namespace = namespace;
        return this;
    }

    private void pattern(Pattern pattern, Handle<?> handle) {
        this.pattern = pattern;
        this.matcher = pattern.matcher(declaration);
        start(handle);
    }

    public <T extends DynamicClassHandle> T parseClass(T classHandle) {
        pattern(CLASS, classHandle);

        String packageName = group("package");
        if (packageName != null && !packageName.isEmpty()) {
            for (MinecraftPackage mcPackage : MinecraftPackage.values()) {
                packageName = packageName.replace(mcPackage.name().toLowerCase() + '.', mcPackage.getPackageId() + '.');
            }
            classHandle.inPackage(packageName);
        }

        // String classGeneric = parser.group("generic");
        classHandle.named(group("className").split("\\$"));

        return classHandle;
    }

    public <T extends ConstructorMemberHandle> T parseConstructor(T ctorHandle) {
        pattern(METHOD, ctorHandle);
        if (has("parameters")) ctorHandle.parameters(parseTypes(group("parameters").split(",")));
        return ctorHandle;
    }

    public <T extends MethodMemberHandle> T parseMethod(T methodHandle) {
        pattern(METHOD, methodHandle);

        // String classGeneric = parser.group("generic");
        methodHandle.named(group("methodName").split("\\$"));
        methodHandle.returns(parseType(group("methodReturnType")));
        if (has("parameters")) methodHandle.parameters(parseTypes(group("parameters").split(",")));

        return methodHandle;
    }

    public <T extends FieldMemberHandle> T parseField(T fieldHandle) {
        pattern(FIELD, fieldHandle);

        // String classGeneric = parser.group("generic");
        fieldHandle.named(group("fieldName").split("\\$"));
        fieldHandle.returns(parseType(group("fieldType")));

        return fieldHandle;
    }

    private String group(String groupName) {
        return this.matcher.group(groupName);
    }

    private boolean has(String groupName) {
        String group = group(groupName);
        return group != null && !group.isEmpty();
    }

    private void start(Handle<?> handle) {
        if (!matcher.matches()) error("Not a " + handle + " declaration");
        parseFlags();
        if (handle instanceof MemberHandle) {
            MemberHandle memberHandle = (MemberHandle) handle;
            if (hasOneOf(flags, Flag.PRIVATE, Flag.PROTECTED)) {
                memberHandle.makeAccessible();
            }
            if (handle instanceof FieldMemberHandle) {
                if (flags.contains(Flag.FINAL)) ((FieldMemberHandle) handle).asFinal();
            }
            if (handle instanceof NamedMemberHandle) {
                if (flags.contains(Flag.STATIC)) ((NamedMemberHandle) handle).asStatic();
            }
        }
    }

    private <T> boolean hasOneOf(Collection<T> collection, T... elements) {
        return Arrays.stream(elements).anyMatch(collection::contains);
    }

    private void parseFlags() {
        if (!has("flags")) return;
        String flagsStr = group("flags");

        for (String flag : flagsStr.split("\\s+")) {
            if (!flags.add(Flag.valueOf(flag.toUpperCase()))) {
                error("Repeated flag: " + flag);
            }
        }

        if (containsDuplicates(flags, Flag.PUBLIC, Flag.PROTECTED, Flag.PRIVATE)) {
            error("Duplicate visibility flags");
        }
    }

    private static <T> boolean containsDuplicates(Collection<T> collection, T... values) {
        boolean contained = false;
        for (T value : values) {
            if (collection.contains(value)) {
                if (contained) return true;
                else contained = true;
            }
        }
        return false;
    }

    private void error(String message) {
        throw new RuntimeException(message + " in: " + declaration + " (RegEx: " + pattern.pattern() + "), (Imports: " + imports + ')');
    }
}

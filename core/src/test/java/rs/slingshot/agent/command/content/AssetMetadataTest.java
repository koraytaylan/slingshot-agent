// SPDX-License-Identifier: MIT OR Apache-2.0
// Copyright 2026 Koray Taylan Davgana

package rs.slingshot.agent.command.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import rs.slingshot.agent.contract.AgentContract;

/** Independent binary length observations, without opening or converting binary data. */
final class AssetMetadataTest {

    private static final String ROOT = "/content/dam/synthetic-binary-length";
    private static final long ORIGINAL_LENGTH = 9;
    private static final AgentContract CONTRACT =
            assertInstanceOf(AgentContract.Loaded.class, AgentContract.load()).contract();

    @Test
    void binaryLengthInspectionDoesNotReadItsValueOrStream() {
        final List<String> accesses = new ArrayList<>();
        final var original = original(property(false, PropertyType.BINARY, accesses));
        assertEquals(ORIGINAL_LENGTH, AssetMetadata.describe(asset(original), CONTRACT).byteLength());
        assertEquals(List.of("isMultiple", "getType", "getLength"), accesses);
    }

    @Test
    void stringPropertyLengthCannotImpersonateBinaryLength() {
        final List<String> accesses = new ArrayList<>();
        assertEquals(FindAssetsByMetadataResult.NO_SIZE,
                AssetMetadata.describe(asset(original(property(false, PropertyType.STRING, accesses))),
                        CONTRACT).byteLength());
        assertEquals(List.of("isMultiple", "getType"), accesses);
    }

    @Test
    void multipleBinaryPropertiesHaveNoSingleOriginalLength() {
        final List<String> accesses = new ArrayList<>();
        assertEquals(FindAssetsByMetadataResult.NO_SIZE,
                AssetMetadata.describe(asset(original(property(true, PropertyType.BINARY, accesses))),
                        CONTRACT).byteLength());
        assertEquals(List.of("isMultiple"), accesses);
    }

    private static Property property(boolean multiple, int type, List<String> accesses) {
        return proxy(Property.class, method -> {
            accesses.add(method);
            return switch (method) {
                case "isMultiple" -> multiple;
                case "getType" -> type;
                case "getLength" -> ORIGINAL_LENGTH;
                default -> throw new AssertionError("binary content was accessed: " + method);
            };
        });
    }

    private static Resource original(Property property) {
        final var node = proxy(Node.class, method -> switch (method) {
            case "hasProperty" -> true;
            case "getProperty" -> property;
            default -> throw new AssertionError("unexpected node access: " + method);
        });
        return proxy(Resource.class, method -> switch (method) {
            case "adaptTo" -> node;
            case "getValueMap" -> new ValueMapDecorator(new HashMap<>(Map.of()));
            default -> throw new AssertionError("unexpected original access: " + method);
        });
    }

    private static Resource asset(Resource original) {
        return Resource.class.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{Resource.class}, (held, method, arguments) -> switch (method.getName()) {
                    case "getPath" -> ROOT;
                    case "getChild" -> "jcr:content/renditions/original/jcr:content".equals(arguments[0])
                            ? original : null;
                    default -> throw new AssertionError("unexpected asset access: " + method.getName());
                }));
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> answer) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{type},
                (held, method, arguments) -> answer.apply(method.getName())));
    }
}

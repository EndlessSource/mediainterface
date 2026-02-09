package org.endlesssource.mediainterface.linux;

import org.freedesktop.dbus.types.Variant;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class MprisMetadataUtils {
    private MprisMetadataUtils() {
    }

    static Optional<Map<String, Object>> toMetadataMap(Object metadata) {
        if (metadata == null) {
            return Optional.empty();
        }

        Object value;
        if (metadata instanceof Variant<?> metadataVariant) {
            // Handle wrapped Variant case (most MPRIS implementations)
            value = metadataVariant.getValue();
        } else if (metadata instanceof Map<?, ?>) {
            // Handle direct Map case (Firefox and some other implementations)
            value = metadata;
        } else {
            return Optional.empty();
        }

        if (!(value instanceof Map<?, ?> rawMetadata) || rawMetadata.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(normalizeMap(rawMetadata));
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> rawMap) {
        Map<String, Object> normalized = new HashMap<>();
        rawMap.forEach((key, rawValue) -> {
            if (key instanceof String keyStr) {
                normalized.put(keyStr, unwrap(rawValue));
            }
        });
        return normalized;
    }

    static Object unwrap(Object value) {
        if (value instanceof Variant<?> variant) {
            return unwrap(variant.getValue());
        }

        if (value instanceof Map<?, ?> nestedMap) {
            return normalizeMap(nestedMap);
        }

        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(MprisMetadataUtils::unwrap)
                    .collect(Collectors.toList());
        }

        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> unwrapped = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                unwrapped.add(unwrap(Array.get(value, i)));
            }
            return unwrapped;
        }

        return value;
    }
}

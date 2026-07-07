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
        if (metadata instanceof Variant<?>) {
            // Handle wrapped Variant case (most MPRIS implementations)
            value = ((Variant<?>) metadata).getValue();
        } else if (metadata instanceof Map<?, ?>) {
            // Handle direct Map case (Firefox and some other implementations)
            value = metadata;
        } else {
            return Optional.empty();
        }

        if (!(value instanceof Map<?, ?>) || ((Map<?, ?>) value).isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(normalizeMap((Map<?, ?>) value));
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> rawMap) {
        Map<String, Object> normalized = new HashMap<>();
        rawMap.forEach((key, rawValue) -> {
            if (key instanceof String) {
                normalized.put((String) key, unwrap(rawValue));
            }
        });
        return normalized;
    }

    static Object unwrap(Object value) {
        if (value instanceof Variant<?>) {
            return unwrap(((Variant<?>) value).getValue());
        }

        if (value instanceof Map<?, ?>) {
            return normalizeMap((Map<?, ?>) value);
        }

        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).stream()
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

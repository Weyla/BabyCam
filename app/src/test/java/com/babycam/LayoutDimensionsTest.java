package com.babycam;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Detect missing layout dimensions even on hidden views, which Android still inflates. */
public class LayoutDimensionsTest {
    @Test public void everyLayoutViewHasWidthAndHeightDirectlyOrThroughStyle() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Map<String, Element> styles = new HashMap<>();
        NodeList definitions = factory.newDocumentBuilder().parse(new File("src/main/res/values/styles.xml"))
                .getElementsByTagName("style");
        for (int i = 0; i < definitions.getLength(); i++) {
            Element style = (Element) definitions.item(i);
            styles.put(style.getAttribute("name"), style);
        }
        File[] layouts = new File("src/main/res/layout").listFiles((dir, name) -> name.endsWith(".xml"));
        assertNotNull(layouts);
        assertTrue(layouts.length > 0);
        for (File layout : layouts) {
            NodeList elements = factory.newDocumentBuilder().parse(layout).getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element view = (Element) elements.item(i);
                for (String dimension : new String[]{"android:layout_width", "android:layout_height"}) {
                    assertTrue(layout.getName() + " " + view.getTagName() + " "
                                    + view.getAttribute("android:id") + " is missing " + dimension,
                            view.hasAttribute(dimension)
                                    || styleHas(styles, view.getAttribute("style"), dimension));
                }
            }
        }
    }

    private boolean styleHas(Map<String, Element> styles, String reference, String dimension) {
        Element style = styles.get(reference.replace("@style/", ""));
        if (style == null) return false;
        NodeList items = style.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            if (dimension.equals(((Element) items.item(i)).getAttribute("name"))) return true;
        }
        return styleHas(styles, style.getAttribute("parent"), dimension);
    }
}

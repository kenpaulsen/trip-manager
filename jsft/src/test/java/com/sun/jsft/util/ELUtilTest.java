package com.sun.jsft.util;

import com.sun.el.ValueExpressionLiteral;
import com.sun.faces.application.ApplicationAssociate;
import com.sun.faces.config.WebConfiguration;
import com.sun.faces.el.ELContextImpl;
import jakarta.el.ExpressionFactory;
import jakarta.el.MapELResolver;
import java.util.HashMap;
import java.util.Map;
import org.mockito.Mockito;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

public class ELUtilTest {
    private static TestFacesContext ctx;
    private static ELUtil elUtil;

    @BeforeClass
    static void setupEL() {
        final TestFacesContext.TestExternalContext extCtx = new TestFacesContext.TestExternalContext();
        final WebConfiguration webConfig = Mockito.mock(WebConfiguration.class);
        extCtx.getApplicationMap().put("com.sun.faces.config.WebConfiguration", webConfig);
        ctx = new TestFacesContext(extCtx);
        ApplicationAssociate assoc = ApplicationAssociate.getInstance(extCtx);
        assoc.setExpressionFactory(ExpressionFactory.newInstance());
        elUtil = ELUtil.getInstance();
        ctx.setElCtx(new ELContextImpl(new MapELResolver()));
    }

    @Test
    void elCanEvaluateNoEL() {
        final Object result = elUtil.eval("hello");
        assertEquals(result, "hello");
    }

    @Test
    void elCanSaveStuff() {
        setVariable("map", new HashMap<>(), Map.class);

        elUtil.setELValue("#{map.foo}", "bar");
        final Object result = elUtil.eval("#{map.foo}");
        assertEquals(result, "bar");
    }

    @Test
    void elCanEvalMultipleExpressions() {
        final String name = "Joe";
        final String phone = "555-555-5555";
        final int age = 22;
        savePerson(name, age, phone);
        final String template = "Hello #{person.name}!\n\nYour age: #{person.age}\n\nYour phone #: #{person.phone}.";

        final Object result = elUtil.eval(template);
        assertEquals(result, template
                .replace("#{person.name}", name)
                .replace("#{person.age}", "" + age)
                .replace("#{person.phone}", phone));
    }

    @Test
    void canLoadTemplateFromFile() {
        final String name = "Jack";
        final String phone = "123-123-1234";
        final int age = 32;
        savePerson(name, age, phone);
        final String template = elUtil.readFile("test.tpl");
        final Object result = elUtil.eval(template);
        assertTrue(result.toString().length() > 100);
        assertEquals(result, template
                .replace("#{person.name}", name)
                .replace("#{person.age}", "" + age)
                .replace("#{person.age + 1}", "" + (age + 1))
                .replace("#{person.phone}", phone));
    }

    private void savePerson(final String name, final int age, final String phone) {
        setVariable("person", new HashMap<>(), Object.class);
        elUtil.setELValue("#{person.name}", name);
        elUtil.setELValue("#{person.age}", age);
        elUtil.setELValue("#{person.phone}", phone);
    }

    private <T> void setVariable(final String key, final T value, final Class<T> cls) {
        ctx.getELContext().getVariableMapper().setVariable(key, new ValueExpressionLiteral(value, cls));
    }
}
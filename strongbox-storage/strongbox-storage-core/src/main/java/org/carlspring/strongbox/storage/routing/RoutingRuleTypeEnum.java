package org.carlspring.strongbox.storage.routing;

import java.util.stream.Stream;

/**
 * @author mtodorov
 */
public enum RoutingRuleTypeEnum
{

    ACCEPT("accept"),

    DENY("deny");


    private String type;


    RoutingRuleTypeEnum(String type)
    {
        this.type = type;
    }

    public String getType()
    {
        return type;
    }

    public static RoutingRuleTypeEnum of(String type)
    {
        return Stream.of(RoutingRuleTypeEnum.values()).filter(rt -> rt.type.equals(type)).findFirst().orElseThrow(
                () -> new IllegalStateException(String.format("Illegal type %s of routing rule", type)));
    }

}

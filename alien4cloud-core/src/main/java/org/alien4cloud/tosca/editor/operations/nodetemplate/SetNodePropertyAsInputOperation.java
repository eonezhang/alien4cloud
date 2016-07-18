package org.alien4cloud.tosca.editor.operations.nodetemplate;

/**
 * Allows to affect a get_input function to the property of a node.
 */
public class SetNodePropertyAsInputOperation {
    /** Id of the property */
    private String propertyName;
    /** The id of the input to associate to the property. */
    private String inputName;
}
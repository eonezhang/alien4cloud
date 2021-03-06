package alien4cloud.deployment.matching.services.nodes;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.alien4cloud.tosca.exceptions.ConstraintValueDoNotMatchPropertyTypeException;
import org.alien4cloud.tosca.exceptions.ConstraintViolationException;
import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.PropertyDefinition;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.definitions.constraints.IMatchPropertyConstraint;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.alien4cloud.tosca.model.types.CapabilityType;
import org.alien4cloud.tosca.model.types.NodeType;
import org.alien4cloud.tosca.normative.types.IPropertyType;
import org.alien4cloud.tosca.normative.types.ToscaTypes;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;

import alien4cloud.deployment.matching.plugins.INodeMatcherPlugin;
import alien4cloud.model.deployment.matching.MatchingConfiguration;
import alien4cloud.model.deployment.matching.MatchingFilterDefinition;
import alien4cloud.model.orchestrators.locations.LocationResourceTemplate;
import alien4cloud.model.orchestrators.locations.LocationResources;
import alien4cloud.utils.AlienUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of INodeMatcherPlugin to be used when no matching plugin has been defined.
 */
@Slf4j
@Component
public class DefaultNodeMatcher implements INodeMatcherPlugin {
    // TODO initialize default matching configuration based on parsing a yaml file within a4c for nodes like Compute etc.
    /**
     * Match a node against a location.
     *
     * @param nodeTemplate The node template to match.
     * @param nodeType The node type that defines the type of the node template to match.
     * @param locationResources The resources configured for the location against which we are matching the nodes.
     */
    public List<LocationResourceTemplate> matchNode(NodeTemplate nodeTemplate, NodeType nodeType, LocationResources locationResources,
            Map<String, MatchingConfiguration> matchingConfigurations) {
        List<LocationResourceTemplate> matchingResults = Lists.newArrayList();

        List<LocationResourceTemplate> matched = matchedOnDemandsAndServices(nodeTemplate, nodeType, locationResources, matchingConfigurations);
        matchingResults.addAll(matched);

        return matchingResults;
    }

    /**
     * Match a node against the on demand resources provided by a location.
     *
     * @param nodeTemplate The node template to match.
     * @param nodeType The node type that defines the type of the node template to match.
     * @param locationResources The resources configured for the location against which we are matching the nodes.
     */
    private List<LocationResourceTemplate> matchedOnDemandsAndServices(NodeTemplate nodeTemplate, NodeType nodeType, LocationResources locationResources,
            Map<String, MatchingConfiguration> matchingConfigurations) {
        /*
         * TODO Refine node matching by considering specific matching rules for the node. If no constraint is specified in a matching configuration then equals
         * TODO constraint is applied.
         */
        List<LocationResourceTemplate> matchingResults = Lists.newArrayList();
        List<LocationResourceTemplate> candidates = locationResources.getNodeTemplates();
        for (LocationResourceTemplate candidate : candidates) {
            String candidateTypeName = candidate.getTemplate().getType();
            NodeType candidateType = locationResources.getNodeTypes().get(candidateTypeName);

            // For the moment only match by node type
            if (isValidCandidate(nodeTemplate, nodeType, candidate, candidateType, locationResources.getCapabilityTypes(), matchingConfigurations)) {
                matchingResults.add(candidate);
            }
        }

        // TODO Sort the matching results to get the best match for the driver.
        return matchingResults;
    }

    /**
     * Checks if the type of a LocationResourceTemplate is matching the expected type.
     *
     * @param nodeTemplate The node template to match.
     * @param nodeType the node type of the node template to match
     * @param candidateType The type of the candidate node.
     * @param candidate The candidate location resource.
     * @param capabilityTypes Map of capability types that may be used by the candidateType.
     * @return True if the candidate is a valid match for the node template.
     */
    private boolean isValidCandidate(NodeTemplate nodeTemplate, NodeType nodeType, LocationResourceTemplate candidate, NodeType candidateType,
            Map<String, CapabilityType> capabilityTypes, Map<String, MatchingConfiguration> matchingConfigurations) {
        // Check that the candidate node type is valid
        if (!isCandidateTypeValid(nodeTemplate, candidateType)) {
            return false;
        }

        // Only abstract node type can be match against a service
        if (!nodeType.isAbstract() && candidate.isService()) {
            return false;
        }

        // The matchingConfigurations can be null when the associate orchestrator is disabled
        // FIXME ad if the associated orch plugin do not rovide matching configuration??
        if (matchingConfigurations == null) {
            return false;
        }

        // Check that the note template properties are matching the constraints specified for matching.
        MatchingConfiguration matchingConfiguration = getMatchingConfiguration(candidateType, matchingConfigurations);

        if (matchingConfiguration == null) {
            return true;
        }

        // create a node filter based on all properties configured on the candidate node
        return isTemplatePropertiesMatchCandidateFilters(nodeTemplate, matchingConfiguration, candidate, candidateType, capabilityTypes);
    }

    /**
     *
     * Get the matching configuration for the substitution candidate based on its type hierarchy.
     *
     * Meaning if a candidateType D derives from (in this order) C, B, A, then we will first look for a matching for D. <br>
     * If not found, then look for the closest parent matching configuration, and so on until no more parent left.
     *
     * @param candidateType
     * @param matchingConfigurations
     * @return
     */
    private MatchingConfiguration getMatchingConfiguration(NodeType candidateType, Map<String, MatchingConfiguration> matchingConfigurations) {
        MatchingConfiguration config = null;
        if (MapUtils.isNotEmpty(matchingConfigurations)) {
            List<String> typeHierarchy = Lists.newArrayList(candidateType.getElementId());
            typeHierarchy.addAll(AlienUtils.safe(candidateType.getDerivedFrom()));

            Iterator<String> iter = typeHierarchy.iterator();
            while (config == null && iter.hasNext()) {
                config = matchingConfigurations.get(iter.next());
            }
        }
        return config;
    }

    private boolean isTemplatePropertiesMatchCandidateFilters(NodeTemplate nodeTemplate, MatchingConfiguration matchingConfiguration,
            LocationResourceTemplate candidate, NodeType candidateType, Map<String, CapabilityType> capabilityTypes) {
        // check that the node root properties matches the filters defined on the MatchingConfigurations.
        if (!isTemplatePropertiesMatchCandidateFilter(nodeTemplate.getProperties(), matchingConfiguration.getProperties(),
                candidate.getTemplate().getProperties(), candidateType.getProperties())) {
            return false;
        }

        if (matchingConfiguration.getCapabilities() == null) {
            // no constraints on capabilities.
            return true;
        }

        // check that the properties defined on the capabilities matches the filters configured for the capabilities
        for (Map.Entry<String, MatchingFilterDefinition> capabilityMatchingFilterEntry : matchingConfiguration.getCapabilities().entrySet()) {
            Capability candidateCapability = candidate.getTemplate().getCapabilities().get(capabilityMatchingFilterEntry.getKey());
            CapabilityType capabilityType = capabilityTypes.get(candidateCapability.getType());
            Capability templateCapability = nodeTemplate.getCapabilities().get(capabilityMatchingFilterEntry.getKey());

            if (templateCapability != null && !isTemplatePropertiesMatchCandidateFilter(templateCapability.getProperties(),
                    capabilityMatchingFilterEntry.getValue().getProperties(), candidateCapability.getProperties(), capabilityType.getProperties())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Add filters ent/ICSARRepositorySearchService.java from the matching configuration to the node filter that will be applied for matching only if a value is
     * specified on the configuration
     * template.
     *
     * @param nodeTemplateValues The properties values from the node template to match.
     * @param sourceFilters The filtering map (based on constraints) from matching configuration.
     * @param propertyValues The values defined on the Location Template.
     * @param propertyDefinitions The properties definitions associated with the node.
     */
    private boolean isTemplatePropertiesMatchCandidateFilter(Map<String, AbstractPropertyValue> nodeTemplateValues,
            Map<String, List<IMatchPropertyConstraint>> sourceFilters, Map<String, AbstractPropertyValue> propertyValues,
            Map<String, PropertyDefinition> propertyDefinitions) {
        for (Map.Entry<String, List<IMatchPropertyConstraint>> filterEntry : sourceFilters.entrySet()) {
            AbstractPropertyValue candidatePropertyValue = propertyValues.get(filterEntry.getKey());
            AbstractPropertyValue templatePropertyValue = nodeTemplateValues.get(filterEntry.getKey());
            if (candidatePropertyValue != null && candidatePropertyValue instanceof ScalarPropertyValue && templatePropertyValue != null
                    && templatePropertyValue instanceof ScalarPropertyValue) {
                try {
                    IPropertyType<?> toscaType = ToscaTypes.fromYamlTypeName(propertyDefinitions.get(filterEntry.getKey()).getType());
                    // set the constraint value and add it to the node filter
                    for (IMatchPropertyConstraint constraint : filterEntry.getValue()) {
                        constraint.setConstraintValue(toscaType, ((ScalarPropertyValue) candidatePropertyValue).getValue());
                        try {
                            constraint.validate(toscaType, ((ScalarPropertyValue) templatePropertyValue).getValue());
                        } catch (ConstraintViolationException e) {
                            return false;
                        }
                    }
                } catch (ConstraintValueDoNotMatchPropertyTypeException e) {
                    log.debug("The value of property for a constraint is not valid.", e);
                }
            }
        }
        return true;
    }

    /**
     * Checks if the type of a LocationResourceTemplate is matching the expected type.
     *
     * @param nodeTemplate The node template to match.
     * @param candidateType The type of the candidate node.
     * @return True if the candidate type matches the node template type, false if not.
     */
    private boolean isCandidateTypeValid(NodeTemplate nodeTemplate, NodeType candidateType) {
        return candidateType.getElementId().equals(nodeTemplate.getType())
                || (candidateType.getDerivedFrom() != null && candidateType.getDerivedFrom().contains(nodeTemplate.getType()));
    }
}
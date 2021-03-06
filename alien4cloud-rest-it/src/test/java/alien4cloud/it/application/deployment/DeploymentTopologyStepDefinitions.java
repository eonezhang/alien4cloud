package alien4cloud.it.application.deployment;

import static alien4cloud.utils.AlienUtils.safe;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.alien4cloud.tosca.model.definitions.AbstractPropertyValue;
import org.alien4cloud.tosca.model.definitions.DeploymentArtifact;
import org.alien4cloud.tosca.model.definitions.PropertyValue;
import org.alien4cloud.tosca.model.definitions.ScalarPropertyValue;
import org.alien4cloud.tosca.model.templates.Capability;
import org.alien4cloud.tosca.model.templates.NodeTemplate;
import org.apache.commons.collections4.MapUtils;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.junit.Assert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import alien4cloud.component.repository.ArtifactRepositoryConstants;
import alien4cloud.it.Context;
import alien4cloud.it.utils.RegisteredStringUtils;
import alien4cloud.it.utils.TestUtils;
import alien4cloud.model.application.Application;
import alien4cloud.model.orchestrators.locations.LocationResourceTemplate;
import alien4cloud.rest.application.model.SetLocationPoliciesRequest;
import alien4cloud.rest.application.model.UpdateDeploymentTopologyRequest;
import alien4cloud.rest.deployment.DeploymentTopologyDTO;
import alien4cloud.rest.model.RestResponse;
import alien4cloud.rest.topology.UpdatePropertyRequest;
import alien4cloud.rest.utils.JsonUtil;
import alien4cloud.utils.AlienConstants;
import alien4cloud.utils.MapUtil;
import alien4cloud.utils.PropertyUtil;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import lombok.AllArgsConstructor;
import lombok.Getter;

public class DeploymentTopologyStepDefinitions {

    @When("^I Set the following location policies with orchestrator \"([^\"]*)\" for groups$")
    public void I_Set_the_following_location_policies_for_groups(String orchestratorName, Map<String, String> locationPolicies) throws Throwable {
        SetLocationPoliciesRequest request = new SetLocationPoliciesRequest();
        String orchestratorId = Context.getInstance().getOrchestratorId(orchestratorName);
        request.setOrchestratorId(orchestratorId);
        Map<String, String> formatedPolicies = Maps.newHashMap();
        for (Entry<String, String> entry : locationPolicies.entrySet()) {
            formatedPolicies.put(entry.getKey(), Context.getInstance().getLocationId(orchestratorId, entry.getValue()));
        }
        request.setGroupsToLocations(formatedPolicies);

        Application application = Context.getInstance().getApplication();
        String environmentId = Context.getInstance().getDefaultApplicationEnvironmentId(application.getName());
        String restUrl = String.format("/rest/v1/applications/%s/environments/%s/deployment-topology/location-policies", application.getId(), environmentId);
        String response = Context.getRestClientInstance().postJSon(restUrl, JsonUtil.toString(request));
        Context.getInstance().registerRestResponse(response);
    }

    @Then("^the deployment topology shoud have the following location policies$")
    public void The_deployment_topology_shoud_have_the_following_location_policies(List<LocationPolicySetting> expectedLocationPoliciesSettings)
            throws Throwable {
        String response = Context.getInstance().getRestResponse();
        RestResponse<DeploymentTopologyDTO> deploymentTopologyDTO = JsonUtil.read(response, DeploymentTopologyDTO.class, Context.getJsonMapper());
        assertNotNull(deploymentTopologyDTO.getData());
        Map<String, String> policies = deploymentTopologyDTO.getData().getLocationPolicies();
        assertNotNull(policies);
        Context context = Context.getInstance();
        for (LocationPolicySetting expected : expectedLocationPoliciesSettings) {
            String expectLocationId = context.getLocationId(context.getOrchestratorId(expected.getOrchestratorName()), expected.getLocationName());
            assertEquals(expectLocationId, policies.get(expected.getGroupName()));
        }
    }

    @When("^I Set a unique location policy to \"([^\"]*)\"/\"([^\"]*)\" for all nodes$")
    public void I_Set_a_unique_location_policy_to_for_all_nodes(String orchestratorName, String locationName) throws Throwable {
        I_Set_the_following_location_policies_for_groups(orchestratorName,
                MapUtil.newHashMap(new String[] { AlienConstants.GROUP_ALL }, new String[] { locationName }));
    }

    @When("^I get the deployment topology for the current application$")
    public void I_get_the_deployment_toology_for_the_current_application() throws Throwable {
        Application application = Context.getInstance().getApplication();
        String environmentId = Context.getInstance().getDefaultApplicationEnvironmentId(application.getName());
        String restUrl = String.format("/rest/v1/applications/%s/environments/%s/deployment-topology/", application.getId(), environmentId);
        String response = Context.getRestClientInstance().get(restUrl);
        Context.getInstance().registerRestResponse(response);
    }

    @When("^I get the deployment topology for the current application on the environment \"(.*?)\"$")
    public void I_get_the_deployment_topology_for_the_current_application(String environment) throws Throwable {
        Application application = Context.getInstance().getApplication();
        String environmentId = Context.getInstance().getApplicationEnvironmentId(application.getName(), environment);
        String restUrl = String.format("/rest/v1/applications/%s/environments/%s/deployment-topology/", application.getId(), environmentId);
        String response = Context.getRestClientInstance().get(restUrl);
        Context.getInstance().registerRestResponse(response);
    }

    @When("^I substitute on the current application the node \"(.*?)\" with the location resource \"(.*?)\"/\"(.*?)\"/\"(.*?)\"$")
    public void I_substitute_on_the_current_application_the_node_with_the_location_resource(String nodeName, String orchestratorName, String locationName,
            String resourceName) throws Throwable {

        Context context = Context.getInstance();
        String orchestratorId = context.getOrchestratorId(orchestratorName);
        String locationId = context.getLocationId(orchestratorId, locationName);
        String resourceId = context.getLocationResourceId(orchestratorId, locationId, resourceName);
        doSubstitution(nodeName, resourceId);
    }

    private DeploymentTopologyDTO getDTOAndassertNotNull() throws IOException {
        String response = Context.getInstance().getRestResponse();
        DeploymentTopologyDTO dto = JsonUtil.read(response, DeploymentTopologyDTO.class, Context.getJsonMapper()).getData();
        assertNotNull(dto);
        return dto;
    }

    @Then("^The deployment topology should have the substituted nodes$")
    public void The_deployment_topology_sould_have_the_substituted_nodes(List<NodeSubstitutionSetting> expectedSubstitutionSettings) throws Throwable {
        DeploymentTopologyDTO dto = getDTOAndassertNotNull();
        Map<String, String> substitutions = dto.getTopology().getSubstitutedNodes();
        Map<String, LocationResourceTemplate> resources = dto.getLocationResourceTemplates();
        assertTrue(MapUtils.isNotEmpty(substitutions));
        assertTrue(MapUtils.isNotEmpty(resources));
        for (NodeSubstitutionSetting nodeSubstitutionSetting : expectedSubstitutionSettings) {
            String substituteName = substitutions.get(nodeSubstitutionSetting.getNodeName());
            assertEquals(nodeSubstitutionSetting.getResourceName(), substituteName);
            LocationResourceTemplate substitute = resources.get(substituteName);
            assertNotNull(substitute);
            assertEquals(nodeSubstitutionSetting.getResourceType(), substitute.getTypes());
        }
    }

    @When("^I update the property \"(.*?)\" to \"(.*?)\" for the subtituted node \"(.*?)\"$")
    public void I_update_the_property_to_for_the_subtituted_node(String propertyName, String propertyValue, String nodeName) throws Throwable {
        Context context = Context.getInstance();
        Application application = context.getApplication();
        String envId = context.getDefaultApplicationEnvironmentId(application.getName());
        UpdatePropertyRequest request = new UpdatePropertyRequest(propertyName, propertyValue);
        String restUrl = String.format("/rest/v1/applications/%s/environments/%s/deployment-topology/substitutions/%s/properties", application.getId(), envId,
                nodeName);
        String response = Context.getRestClientInstance().postJSon(restUrl, JsonUtil.toString(request));
        context.registerRestResponse(response);
    }

    @Then("^The node \"(.*?)\" in the deployment topology should have the property \"(.*?)\" with value \"(.*?)\"$")
    public void the_node_in_the_deployment_topology_should_have_the_property_with_value(String nodeName, String propertyName, String expectPropertyValue)
            throws Throwable {
        DeploymentTopologyDTO dto = getDeploymentTopologyDTO();
        NodeTemplate node = dto.getTopology().getNodeTemplates().get(nodeName);
        assertNodePropertyValueEquals(node, propertyName, expectPropertyValue);
    }

    @When("^I update the capability \"(.*?)\" property \"(.*?)\" to \"(.*?)\" for the subtituted node \"(.*?)\"$")
    public void i_update_the_capability_property_to_for_the_subtituted_node(String capabilityName, String propertyName, String propertyValue, String nodeName)
            throws Throwable {
        Context context = Context.getInstance();
        Application application = context.getApplication();
        String envId = context.getDefaultApplicationEnvironmentId(application.getName());
        UpdatePropertyRequest request = new UpdatePropertyRequest(propertyName, propertyValue);
        String restUrl = String.format("/rest/v1/applications/%s/environments/%s/deployment-topology/substitutions/%s/capabilities/%s/properties",
                application.getId(), envId, nodeName, capabilityName);
        String response = Context.getRestClientInstance().postJSon(restUrl, JsonUtil.toString(request));
        context.registerRestResponse(response);
    }

    @Then("^The the node \"(.*?)\" in the deployment topology should have the capability \"(.*?)\"'s property \"(.*?)\" with value \"(.*?)\"$")
    public void the_the_node_in_the_deployment_topology_should_have_the_capability_s_property_with_value(String nodeName, String capabilityName,
            String propertyName, String expectedPropertyValue) throws Throwable {
        DeploymentTopologyDTO dto = getDeploymentTopologyDTO();
        NodeTemplate node = dto.getTopology().getNodeTemplates().get(nodeName);
        assertCapabilityPropertyValueEquals(node, capabilityName, propertyName, expectedPropertyValue);
    }

    @When("^I set the following inputs properties$")
    public void I_set_the_following_inputs_properties(Map<String, Object> inputProperties) throws Throwable {
        UpdateDeploymentTopologyRequest request = new UpdateDeploymentTopologyRequest();
        request.setInputProperties(parseAndReplaceProperties(inputProperties));
        executeUpdateDeploymentTopologyCall(request);
    }

    private Map<String, Object> parseAndReplaceProperties(Map<String, Object> inputProperties) {
        Map<String, Object> result = Maps.newHashMap();
        for (Entry<String, Object> entry : inputProperties.entrySet()) {
            result.put(entry.getKey(), RegisteredStringUtils.parseAndReplace(entry.getValue()));
        }
        return result;
    }

    @When("^I set the following orchestrator properties$")
    public void I_set_the_following_orchestrator_properties(Map<String, String> orchestratorProperties) throws Throwable {
        UpdateDeploymentTopologyRequest request = new UpdateDeploymentTopologyRequest();
        request.setProviderDeploymentProperties(orchestratorProperties);
        executeUpdateDeploymentTopologyCall(request);
    }

    @Then("^the deployment topology should have the following inputs properties$")
    public void The_deployment_topology_sould_have_the_following_input_properties(Map<String, String> expectedStringInputProperties) throws Throwable {
        DeploymentTopologyDTO dto = getDTOAndassertNotNull();
        Map<String, AbstractPropertyValue> expectedInputProperties = Maps.newHashMap();
        for (Entry<String, String> inputEntry : expectedStringInputProperties.entrySet()) {
            expectedInputProperties.put(inputEntry.getKey(), new ScalarPropertyValue(inputEntry.getValue()));
        }
        assertPropMapContains(dto.getTopology().getInputProperties(), expectedInputProperties);
    }

    @Then("^the deployment topology should have the following orchestrator properties$")
    public void The_deployment_topology_sould_have_the_following_orchestrator_properties(Map<String, String> expectedInputProperties) throws Throwable {
        DeploymentTopologyDTO dto = getDTOAndassertNotNull();
        assertMapContains(dto.getTopology().getProviderDeploymentProperties(), expectedInputProperties);
    }

    @Then("^the following nodes properties values sould be \"(.*?)\"$")
    public void The_following_nodes_properties_values_should_be(String expectedValue, Map<String, String> nodesProperties) throws Throwable {
        DeploymentTopologyDTO dto = getDTOAndassertNotNull();
        for (Entry<String, String> entry : nodesProperties.entrySet()) {
            NodeTemplate template = MapUtils.getObject(dto.getTopology().getNodeTemplates(), entry.getKey());
            assertNodePropertyValueEquals(template, entry.getValue(), expectedValue);
        }
    }

    private void executeUpdateDeploymentTopologyCall(UpdateDeploymentTopologyRequest request) throws IOException, JsonProcessingException {
        Application application = Context.getInstance().getApplication();
        String envId = Context.getInstance().getDefaultApplicationEnvironmentId(application.getName());
        String restUrl = String.format("/rest/v1/applications/%s/environments/%s/deployment-topology", application.getId(), envId);
        String response = Context.getRestClientInstance().putJSon(restUrl, JsonUtil.toString(request));
        Context.getInstance().registerRestResponse(response);
    }

    private void assertNodePropertyValueEquals(NodeTemplate node, String propertyName, String expectedPropertyValue) {
        assertNotNull(node);
        AbstractPropertyValue abstractProperty = MapUtils.getObject(node.getProperties(), propertyName);
        assertEquals(expectedPropertyValue, PropertyUtil.getScalarValue(abstractProperty));
    }

    private void assertCapabilityPropertyValueEquals(NodeTemplate node, String capabilityName, String propertyName, String expectedPropertyValue) {
        assertNotNull(node);
        Capability capability = MapUtils.getObject(node.getCapabilities(), capabilityName);
        assertNotNull(capability);
        AbstractPropertyValue abstractProperty = MapUtils.getObject(capability.getProperties(), propertyName);
        assertEquals(expectedPropertyValue, PropertyUtil.getScalarValue(abstractProperty));
    }

    private void assertMapContains(Map<String, String> map, Map<String, String> expectedMap) {
        for (Entry<String, String> entry : expectedMap.entrySet()) {
            assertEquals(entry.getValue(), MapUtils.getObject(map, entry.getKey()));
        }
    }

    private void assertPropMapContains(Map<String, PropertyValue> map, Map<String, AbstractPropertyValue> expectedMap) {
        map = safe(map);
        for (Entry<String, AbstractPropertyValue> entry : expectedMap.entrySet()) {
            assertEquals(entry.getValue(), map.get(entry.getKey()));
        }
    }

    @When("^I upload a file located at \"([^\"]*)\" for the input artifact \"([^\"]*)\"$")
    public void iUploadAFileLocatedAtForTheInputArtifact(String localFile, String inputArtifactName) throws Throwable {
        Application application = Context.getInstance().getApplication();
        String envId = Context.getInstance().getDefaultApplicationEnvironmentId(application.getName());
        String url = String.format("/rest/applications/%s/environments/%s/deployment-topology/inputArtifacts/%s/upload", application.getId(), envId,
                inputArtifactName);
        Context.getInstance().registerRestResponse(
                Context.getRestClientInstance().postMultipart(url, Paths.get(localFile).getFileName().toString(), Files.newInputStream(Paths.get(localFile))));
    }

    @When("^I substitute on the current application the node \"([^\"]*)\" with the service resource \"([^\"]*)\"$")
    public void iSubstituteOnTheCurrentApplicationTheNodeWithTheServiceResource(String nodeName, String serviceName) throws Throwable {
        String resourceId = Context.getInstance().getServiceId(serviceName);
        doSubstitution(nodeName, resourceId);
    }

    public void doSubstitution(String nodeName, String resourceId) throws IOException {
        Context context = Context.getInstance();
        Application application = context.getApplication();
        String envId = context.getDefaultApplicationEnvironmentId(application.getName());
        String restUrl = String.format("/rest/v1/applications/%s/environments/%s/deployment-topology/substitutions/%s", application.getId(), envId, nodeName);
        NameValuePair resourceParam = new BasicNameValuePair("locationResourceTemplateId", resourceId);
        String response = Context.getRestClientInstance().postUrlEncoded(restUrl, Lists.newArrayList(resourceParam));
        context.registerRestResponse(response);
    }

    @Then("^the deployment topology should not have any location policies$")
    public void theDeploymentTopologyShouldNotHaveAnyLocationPolicies() throws Throwable {
        DeploymentTopologyDTO dto = getDeploymentTopologyDTO();
        Assert.assertTrue(MapUtils.isEmpty(dto.getLocationPolicies()));
    }

    @Then("^the deployment topology should not have any input properties$")
    public void theDeploymentTopologyShouldNotHaveAnyInputProperties() throws Throwable {
        DeploymentTopologyDTO dto = getDeploymentTopologyDTO();
        Assert.assertTrue(MapUtils.isEmpty(dto.getTopology().getInputProperties()));
    }

    @Then("^the deployment topology should not have any input artifacts$")
    public void theDeploymentTopologyShouldNotHaveAnyInputArtifacts() throws Throwable {
        DeploymentTopologyDTO dto = getDeploymentTopologyDTO();
        Assert.assertTrue(MapUtils.isEmpty(dto.getTopology().getUploadedInputArtifacts()));
    }

    @Then("^the deployment topology should have the following inputs artifacts$")
    public void theDeploymentTopologyShouldHaveTheFollowingInputsArtifacts(Map<String, String> expectedArtifacts) throws Throwable {
        DeploymentTopologyDTO dto = getDeploymentTopologyDTO();
        Map<String, String> uploadedArtifacts = dto.getTopology().getUploadedInputArtifacts().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getArtifactName()));
        Assert.assertEquals(expectedArtifacts, uploadedArtifacts);
    }

    @Then("^the deployment topology should not have the following input properties$")
    public void theDeploymentTopologyShouldNotHaveTheFollowingInputProperties(List<String> expectedMissings) throws Throwable {
        DeploymentTopologyDTO dto = getDeploymentTopologyDTO();
        Set<String> actualInputs = safe(dto.getTopology().getInputProperties()).keySet();
        for (String expectedMissing : expectedMissings) {
            Assert.assertFalse("\"" + expectedMissing + "\" should not appear in inputs", actualInputs.contains(expectedMissing));
        }
    }

    private DeploymentTopologyDTO getDeploymentTopologyDTO() throws IOException {
        DeploymentTopologyDTO dto = JsonUtil.read(Context.getInstance().getRestResponse(), DeploymentTopologyDTO.class, Context.getJsonMapper()).getData();
        assertNotNull(dto);
        return dto;
    }

    @When("^I select the file \"([^\"]*)\" from the current topology archive for the input artifact \"([^\"]*)\"$")
    public void iSelectTheFileFromTheCurrentTopologyArchiveForTheInputArtifact(String fileRef, String inputArtifactName) throws Throwable {
        Application application = Context.getInstance().getApplication();
        String envId = Context.getInstance().getDefaultApplicationEnvironmentId(application.getName());
        String url = String.format("/rest/applications/%s/environments/%s/deployment-topology/inputArtifacts/%s/update", application.getId(), envId,
                inputArtifactName);
        DeploymentArtifact inputArtifact = new DeploymentArtifact();
        inputArtifact.setArchiveName(application.getId());
        inputArtifact.setArchiveVersion(TestUtils.getVersionFromId(Context.getInstance().getTopologyId()));
        inputArtifact.setArtifactRef(fileRef);
        inputArtifact.setArtifactRepository(ArtifactRepositoryConstants.ALIEN_TOPOLOGY_REPOSITORY);

        Context.getInstance().registerRestResponse(Context.getRestClientInstance().postJSon(url, JsonUtil.toString(inputArtifact)));
    }

    @Getter
    @AllArgsConstructor(suppressConstructorProperties = true)
    private static class LocationPolicySetting {
        String groupName;
        String orchestratorName;
        String locationName;
    }

    @Getter
    @AllArgsConstructor(suppressConstructorProperties = true)
    private static class NodeSubstitutionSetting {
        String nodeName;
        String resourceName;
        String resourceType;
    }
}
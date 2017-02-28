package alien4cloud.deployment;

import javax.inject.Inject;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import alien4cloud.model.deployment.Deployment;
import alien4cloud.model.deployment.DeploymentTopology;
import alien4cloud.orchestrators.plugin.IOrchestratorPlugin;
import alien4cloud.paas.IPaaSCallback;
import alien4cloud.paas.OrchestratorPluginService;
import alien4cloud.paas.model.PaaSDeploymentContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages topology un-deployment.
 */
@Service
@Slf4j
public class UndeployService {
    @Inject
    private OrchestratorPluginService orchestratorPluginService;
    @Inject
    private DeploymentService deploymentService;
    @Inject
    private DeploymentRuntimeStateService deploymentRuntimeStateService;
    @Inject
    private DeploymentLockService deploymentLockService;

    /**
     * Un-deploy a deployment object
     *
     * @param deploymentId deployment id to deploy
     */
    public void undeploy(String deploymentId) {
        deploymentLockService.doWithDeploymentWriteLock(() -> {
            Deployment deployment = deploymentService.getOrfail(deploymentId);
            undeploy(deployment);
            return null;
        });
    }

    public void undeployEnvironment(String environmentId) {
        deploymentLockService.doWithDeploymentWriteLock(() -> {
            Deployment deployment = deploymentService.getActiveDeployment(environmentId);
            if (deployment != null) {
                undeploy(deployment);
            } else {
                log.warn("No deployment found for environment " + environmentId);
            }
            return null;
        });
    }

    /**
     * Un-deploy from a deployment setup.
     *
     * @param deploymentTopology setup object containing information to deploy
     */
    public void undeploy(DeploymentTopology deploymentTopology) {
        deploymentLockService.doWithDeploymentWriteLock(() -> {
            Deployment activeDeployment = deploymentService.getActiveDeploymentOrFail(deploymentTopology.getEnvironmentId());
            undeploy(activeDeployment);
            return null;
        });
    }

    private void undeploy(final Deployment deployment) {
        deploymentLockService.doWithDeploymentWriteLock(() -> {
            log.info("Un-deploying deployment [{}] on cloud [{}]", deployment.getId(), deployment.getOrchestratorId());
            IOrchestratorPlugin orchestratorPlugin = orchestratorPluginService.getOrFail(deployment.getOrchestratorId());
            DeploymentTopology deployedTopology = deploymentRuntimeStateService.getRuntimeTopology(deployment.getId());
            PaaSDeploymentContext deploymentContext = new PaaSDeploymentContext(deployment, deployedTopology);
            orchestratorPlugin.undeploy(deploymentContext, new IPaaSCallback<ResponseEntity>() {
                @Override
                public void onSuccess(ResponseEntity data) {
                    deploymentService.markUndeployed(deployment);
                    log.info("Un-deployed deployment [{}] on cloud [{}]", deployment.getId(), deployment.getOrchestratorId());
                }

                @Override
                public void onFailure(Throwable throwable) {
                    log.warn("Fail while Undeploying deployment [{}] on cloud [{}]", deployment.getId(), deployment.getOrchestratorId());
                }
            });
            return null;
        });
    }
}

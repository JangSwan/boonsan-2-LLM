package com.llmserving.membership.recovery;

import com.llmserving.membership.adapter.SpotInstancePort;
import com.llmserving.membership.config.MembershipProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceMarketOptionsRequest;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.MarketType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;

/**
 * AWS 스팟 인스턴스 재생성 호출 (EA SpotInstanceClient, boundary, real 프로필). SpotInstancePort를 구현한다.
 * idempotencyKey를 EC2 clientToken으로 넘겨 같은 키의 두 번째 요청은 같은 인스턴스를 돌려받는다.
 * 자격 증명은 .env의 AWS 환경변수(기본 자격 증명 체인)로 받는다.
 */
@Slf4j
@Component
@Profile("real")
public class SpotInstanceClient implements SpotInstancePort {

    private final Ec2Client ec2;
    private final String launchTemplateId;

    public SpotInstanceClient(MembershipProperties properties) {
        this.ec2 = Ec2Client.builder().region(Region.of(properties.aws().region())).build();
        this.launchTemplateId = properties.aws().launchTemplateId();
    }

    @Override
    public String requestInstance(String idempotencyKey) {
        RunInstancesResponse response = ec2.runInstances(RunInstancesRequest.builder()
                .launchTemplate(LaunchTemplateSpecification.builder().launchTemplateId(launchTemplateId).build())
                .instanceMarketOptions(InstanceMarketOptionsRequest.builder().marketType(MarketType.SPOT).build())
                .clientToken(idempotencyKey)
                .minCount(1)
                .maxCount(1)
                .build());
        String instanceId = response.instances().get(0).instanceId();
        log.info("event=aws.instance-requested instanceId={} msg=AWS 스팟 인스턴스 요청", instanceId);
        return instanceId;
    }
}

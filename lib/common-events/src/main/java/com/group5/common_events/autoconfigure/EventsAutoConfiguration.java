package com.group5.common_events.autoconfigure;

import com.group5.common_events.EventEnvelope;        //Cấu trúc dữ liệu của event
import com.group5.common_events.EventEnvelopeFactory; //Tạo event envelope
import com.group5.common_events.EventKafka;           //Chứa tên bean hoặc các hằng số Kafka dùng chung

import io.micrometer.tracing.Tracer;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
/**
 *  Mọi người có thể đọc code để hiểu sâu hơn mình sẽ nói flow của common-event này
 *  - Tổng quan thì Microservice (eg. commerce-service) sẽ gửi event/sự kiện lên eventEnvelopeFactory
 *  - Sau đó eventKafkaTemplate sẽ serialize EventEnvelope thành JSON
 *  - Apache Kafka lưu và phân phối message dựa theo topic mình đã chốt với nhau
 *  - eventListenerContainerFactory deserialize JSON thành EventEnvelope có payload kiểu JsonNode
 *  - Cuối cùng là thằng Microservice nhận lại event (eg. Inventory Service)
 *
 * */


@AutoConfiguration(after = KafkaAutoConfiguration.class)
//yêu cầu lớp này được xử lý sau cấu hình Kafka tự động tiêu chuẩn.
//Điều này giúp cấu hình của thư viện dựa trên các thiết lập Kafka chung đã có.
@ConditionalOnClass(KafkaTemplate.class)
//Chỉ áp dụng cấu hình khi KafkaTemplate có mặt trên classpath.
public class EventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventEnvelopeFactory eventEnvelopeFactory(ObjectProvider<Tracer> tracer, ObjectProvider<Clock> clock) {
        return new EventEnvelopeFactory(
                tracer.getIfAvailable(() -> Tracer.NOOP),
                //Nếu Spring có Tracer, sử dụng bean đó.
                //
                //Nếu không có, sử dụng Tracer.NOOP, tức tracer không thực hiện tracing thực tế.
                clock.getIfAvailable(Clock::systemUTC));
                //Nếu ứng dụng cung cấp Clock, sử dụng Clock đó.
                //
                //Nếu không, dùng đồng hồ hệ thống theo UTC.
    }

    @Bean(EventKafka.TEMPLATE)
    public KafkaTemplate<String, EventEnvelope<?>> eventKafkaTemplate(
            KafkaProperties properties,
            ObjectProvider<JsonMapper> jsonMapper) {

        JacksonJsonSerializer<EventEnvelope<?>> valueSerializer =
                new JacksonJsonSerializer<>(jsonMapper.getIfAvailable(JsonMapper::shared));
        valueSerializer.setAddTypeInfo(false);

        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(),
                new StringSerializer(),
                valueSerializer));
    }

    //Tạo listener container để quản lý việc tiêu thụ message
    @Bean(EventKafka.LISTENER_CONTAINER_FACTORY)
    @SuppressWarnings("unchecked") //Này tắt cảnh báo thôi
    public ConcurrentKafkaListenerContainerFactory<Object, Object> eventListenerContainerFactory(
            KafkaProperties properties,
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ObjectProvider<JsonMapper> jsonMapper) {


        JsonMapper mapper = jsonMapper.getIfAvailable(JsonMapper::shared);
        JavaType envelopeType = mapper.getTypeFactory().constructParametricType(
                EventEnvelope.class,
                JsonNode.class);
        JacksonJsonDeserializer<EventEnvelope<JsonNode>> valueDeserializer = new JacksonJsonDeserializer<>(envelopeType, mapper, false);


        //Ở đây:
        //- StringDeserializer chuyển key thành String.
        //- valueDeserializer chuyển JSON thành EventEnvelope<JsonNode>.
        //- ErrorHandlingDeserializer bọc deserializer thực tế để tích hợp cơ chế xử lý lỗi deserialize của Spring Kafka.
        ConsumerFactory<String, EventEnvelope<JsonNode>> consumerFactory =
                new DefaultKafkaConsumerFactory<>(
                properties.buildConsumerProperties(),
                new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));
        //Đầu tiên, tạo một listener factory mới.
        //Sau đó, configurer.configure(...) áp dụng cấu hình từ Spring Boot,
        //kiểu như những thiết lập listener được hỗ trợ bởi ConcurrentKafkaListenerContainerFactoryConfigurer.
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();

        //Ép kiểu qua một kiểu wildcard giúp ko làm cho dữ liệu thực tế thay đổi
        configurer.configure(factory, (ConsumerFactory<Object, Object>) (ConsumerFactory<?, ?>) consumerFactory);
        return factory;
    }
}

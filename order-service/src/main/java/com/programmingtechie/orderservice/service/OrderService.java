package com.programmingtechie.orderservice.service;

import com.programmingtechie.orderservice.dto.InventoryResponse;
import com.programmingtechie.orderservice.dto.OrderLineItemsDto;
import com.programmingtechie.orderservice.dto.OrderRequest;
import com.programmingtechie.orderservice.event.OrderPlacedEvent;
import com.programmingtechie.orderservice.model.Order;
import com.programmingtechie.orderservice.model.OrderLineItems;
import com.programmingtechie.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;
    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

    public String placeOrder(OrderRequest orderRequest) {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItems = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToDto)
                .toList();

        order.setOrderLineItemsList(orderLineItems);

       List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .toList();

       log.info("Calling inventory service");

       Span inventoryServiceLookup = tracer.nextSpan().name("InventoryServiceLookup");

       try(Tracer.SpanInScope spanInScope = tracer.withSpan(inventoryServiceLookup.start())){
           //Call Inventory service,and place order if product is in stock
           //whatever body you get convert into instance of inventory response class
           InventoryResponse[] inventoryResponseArray = webClientBuilder.build().get()
                   .uri("http://inventory-service/api/inventory"
                           , uriBuilder -> uriBuilder.queryParam("skuCode",skuCodes).build())
                   .retrieve()
                   .bodyToMono(InventoryResponse[].class)
                   .block();
           boolean allProductsInStock = Arrays.stream(inventoryResponseArray)
                   .allMatch(InventoryResponse::isInStock);
           if(allProductsInStock){
               orderRepository.save(order);
               kafkaTemplate.send("notificationTopic",new OrderPlacedEvent(order.getOrderNumber()));
               return "Order Placed Succesfully";
           }
           else{
               throw new IllegalArgumentException("Product is not in stock, please try again later");
           }
       } finally {
           inventoryServiceLookup.end();
       }
    }
    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }
}

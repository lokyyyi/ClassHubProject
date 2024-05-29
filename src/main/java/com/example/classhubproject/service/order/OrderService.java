package com.example.classhubproject.service.order;

import com.example.classhubproject.exception.ConflictException;
import com.example.classhubproject.data.order.*;
import com.example.classhubproject.mapper.lecture.LectureMapper;
import com.example.classhubproject.mapper.order.OrderMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final LectureMapper lectureMapper;
    private final HttpServletRequest request;


    // 진행중인 주문 목록 조회
    public List<OrderDetailResponseDTO> getInProgressOrdersList(int userId) {
        // 특정 회원의 가장 최근에 생성된 orders_id
        int ordersId = getOrdersIdByUserId(userId);
        // 진행중인 주문 목록 조회
        return orderMapper.getInProgressOrderList(userId, ordersId);
    }

    // 진행중인 주문의 강의 개별 삭제
    @Transactional(rollbackFor = {Exception.class, RuntimeException.class})
    public void deleteOrder(int classId) {
        int userId = getUserId();

        // 주문에서 해당 강의 삭제
        deleteInProgressOrder(classId);

        // 진행 중인 주문 목록 조회
        List<OrderDetailResponseDTO> inProgressOrders = getInProgressOrdersList(userId);

        // 삭제된 강의를 제외한 진행 중 남은 강의 목록 반환
        List<Integer> classIds = updatedInProgressOrdersList(inProgressOrders, classId);

        // 주문 정보 업데이트
        updateOrderInfo(userId, classIds);

    }

    // 특정 사용자의 전체 주문 목록
    public List<OrderResponseDTO> getOrderList(int userId) {
        List<OrderResponseDTO> orderList = orderMapper.getOrderList(userId);

        return orderList.stream()
                .map(this::updateOrderWithOrderName)
                .collect(Collectors.toList());
    }

    // 주문 상세 목록
    public List<OrderDetailResponseDTO> getOrderDetailList(int ordersId) {
        return orderMapper.getOrderDetailList(ordersId);
    }

    // 주문하기
    @Transactional(rollbackFor = {Exception.class, RuntimeException.class})
    public boolean addOrder(List<Integer> classIds) {
        // 세션에서 userId 조회
        int userId = getUserId();

        // 이미 보유한 강의인지 확인
        checkHoldClass(classIds);

        // 총 주문 금액 계산
        int totalPrice = calculateTotalPrice(classIds);

        // 주문 정보 생성
        OrderRequestDTO orders = createOrder(userId, totalPrice);

        // 주문 추가
        int ordersId = insertOrder(orders);

        // 주문 상세 추가
        insertOrderDetails(classIds);

        return true;
    }

    // 세션에서 userId 조회
    /*
    private int getUserId() {
        return (int) request.getSession().getAttribute("userId");
    }
     */

    // 테스트용 하드코딩 (임시)
    private int getUserId() {
        return 3;
    }

    // 특정 회원의 가장 최근에 생성된 주문ID 조회
    private int getOrdersIdByUserId(int userId) {
        return orderMapper.getOrdersIdByUserId(userId);
    }

    // 이미 보유한 강의인지 확인
    private void checkHoldClass(List<Integer> classIds) {
        int userId = getUserId();

        boolean alreadyHold = classIds.stream()
                .anyMatch(classId -> orderMapper.checkHoldClass(classId, userId));

        if (alreadyHold) {
            throw new ConflictException("이미 보유한 강의가 포함되어 있습니다.");
        }

    }

    // 주문별 주문명 생성
    private String createOrderName(int ordersId) {
        int orderDetailCount = orderMapper.getOrderDetailCountByOrdersId(ordersId);
        String className = orderMapper.getClassNameByOrdersId(ordersId);

        String orderName;

        if (orderDetailCount == 1) {
            orderName = className;
        } else {
            orderName = className + " 외 " + (orderDetailCount - 1) + "건";
        }

        return orderName;
    }

    // 총 주문 금액 계산
    private int calculateTotalPrice(List<Integer> classIds) {

        return classIds.stream()
                .mapToInt(lectureMapper::getClassPrice)
                .sum();
    }

    // 주문 정보 생성
    private OrderRequestDTO createOrder(int userId, int totalPrice) {
        return OrderRequestDTO.builder()
                .userId(userId)
                .totalPrice(totalPrice)
                .build();
    }

    // 주문 추가
    private int insertOrder(OrderRequestDTO orderRequestDTO) {
        orderMapper.insertOrder(orderRequestDTO);
        return orderRequestDTO.getOrdersId();
    }

    // 주문 상세 추가
    private void insertOrderDetails(List<Integer> classIds) {
        // 세션에서 userId 조회
        int userId = getUserId();
        // 특정 회원의 가장 최근에 생성된 orders_id
        int ordersId = getOrdersIdByUserId(userId);

        classIds.stream()
                .map(classId -> OrderRequestDTO.builder()
                        .ordersId(ordersId)
                        .classId(classId)
                        .build())
                .forEach(orderMapper::insertOrderDetail);
    }

    // 주문에서 해당 강의 삭제
    private void deleteInProgressOrder(int classId) {
        orderMapper.deleteInProgressOrderByClassId(classId);
    }

    // 삭제된 강의를 제외한 진행 중 남은 강의 목록 반환
    private List<Integer> updatedInProgressOrdersList(List<OrderDetailResponseDTO> orders, int classId) {
        return orders.stream()
                .map(OrderDetailResponseDTO::getClassId)
                .filter(id -> id != classId)
                .collect(Collectors.toList());
    }

    // 진행중 주문 정보 업데이트
    private void updateOrderInfo(int userId, List<Integer> classIds) {
        // 총 주문 금액 재계산
        int totalPrice = calculateTotalPrice(classIds);

        int ordersId = getOrdersIdByUserId(userId);
        if (totalPrice == 0) { // 총 주문 금액이 0이면 해당 주문 삭제
            orderMapper.deleteOrder(ordersId);
        } else { // 총 주문 금액 업데이트
            orderMapper.updateTotalPrice(ordersId, totalPrice);
        }
    }

    // 주문명 포함한 주문 정보 생성
    private OrderResponseDTO updateOrderWithOrderName(OrderResponseDTO order) {
        int ordersId = order.getOrdersId();
        String orderName = createOrderName(ordersId);

        return OrderResponseDTO.builder()
                .ordersId(order.getOrdersId())
                .userId(order.getUserId())
                .totalPrice(order.getTotalPrice())
                .finalOrderStatus(order.getFinalOrderStatus())
                .regdate(order.getRegdate())
                .orderName(orderName)
                .build();
    }
}
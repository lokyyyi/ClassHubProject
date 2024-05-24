package com.example.classhubproject.service.payment;

import com.example.classhubproject.data.payment.PaymentPrepareResponseDTO;
import com.example.classhubproject.data.payment.PaymentRequestDTO;
import com.example.classhubproject.mapper.order.OrderMapper;
import com.example.classhubproject.mapper.payment.PaymentMapper;
import com.siot.IamportRestClient.exception.IamportResponseException;
import com.siot.IamportRestClient.request.*;
import com.siot.IamportRestClient.response.*;
import com.siot.IamportRestClient.IamportClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;

@Slf4j
@Service
public class PaymentService {

    @Autowired
    private IamportClient iamportClient;

    @Autowired
    HttpServletRequest request;

    @Autowired
    PaymentMapper paymentMapper;

    @Autowired
    OrderMapper orderMapper;

    // PaymentPrepareResponseDTO 객체로 변환
    public PaymentPrepareResponseDTO convertToResponseDTO(IamportResponse<Prepare> paymentInfo) {
        PaymentPrepareResponseDTO responseData = new PaymentPrepareResponseDTO();
        if (paymentInfo.getResponse() != null) {
            Prepare prepare = paymentInfo.getResponse();
            try {
                Field merchantUidField = Prepare.class.getDeclaredField("merchant_uid");
                merchantUidField.setAccessible(true);
                String merchantUidValue = (String) merchantUidField.get(prepare);

                Field amountField = Prepare.class.getDeclaredField("amount");
                amountField.setAccessible(true);
                BigDecimal amountValue = (BigDecimal) amountField.get(prepare);

                responseData = PaymentPrepareResponseDTO.builder()
                        .code(paymentInfo.getCode())
                        .message(paymentInfo.getMessage())
                        .merchantUid(merchantUidValue)
                        .amount(amountValue)
                        .build();
            } catch (NoSuchFieldException | IllegalAccessException e) {
                log.error("필드 접근 중 오류 발생: {}", e.getMessage());
            }
        }
        return responseData;
    }

    // 결제 정보 저장
    public int addPayment(String impUid) {
        try {
            // 포트원 결제 정보 가져오기
            IamportResponse<Payment> paymentResponse = iamportClient.paymentByImpUid(impUid);
            // 결제된 금액
            BigDecimal paymentAmount = paymentResponse.getResponse().getAmount();

            int userId = getUserId();

            // 최근 주문 ID 가져오기
            int ordersId = orderMapper.getOrdersIdByUserId(userId);

            // 주문 총 금액 가져오기 (BigDecimal로 형변환)
            BigDecimal totalOrderAmount = new BigDecimal(orderMapper.getTotalPriceByOrdersId(ordersId));

            // 결제된 금액과 주문 총 금액 비교
            if (paymentAmount.compareTo(totalOrderAmount) == 0) {
                Payment payment = paymentResponse.getResponse();
                PaymentRequestDTO paymentInfo = PaymentRequestDTO.builder()
                        .ordersId(ordersId)
                        .impUid(payment.getImpUid())
                        .merchantUid(payment.getMerchantUid())
                        .pgProvider(payment.getPgProvider())
                        .payMethod(payment.getPayMethod())
                        .paymentAmount(payment.getAmount())
                        .paymentStatus(payment.getStatus())
                        .paidAt(payment.getPaidAt())
                        .build();

                paymentMapper.insertPayment(paymentInfo);
                
                // 최종 주문 상태 업데이트
                orderMapper.completedOrder(ordersId);

                return 1;
            } else {
                return 0;
            }
        } catch (IamportResponseException | IOException e) {
            return 2;
        }
    }


    // 결제 취소 시 payment_status & cancelled_at 업데이트
    public int cancelPayment(String impUid) {
        try {
            CancelData cancelData = new CancelData(impUid, true);
            IamportResponse<Payment> cancelResponse = iamportClient.cancelPaymentByImpUid(cancelData);

            int responseCode = cancelResponse.getCode();

            if (responseCode == 0) {
                Payment payment = cancelResponse.getResponse();
                PaymentRequestDTO paymentInfo = PaymentRequestDTO.builder()
                        .impUid(payment.getImpUid())
                        .paymentStatus(payment.getStatus())
                        .cancelledAt(payment.getCancelledAt())
                        .build();

                // 결제 취소 정보 업데이트
                paymentMapper.cancelPayment(paymentInfo);
                
                // 최종 주문 상태 업데이트
                int ordersId = paymentMapper.getOrdersIdByImpUid(paymentInfo.getOrdersId());
                orderMapper.cancelOrder(ordersId);

                return 1;
            } else {
                return 0;
            }
        } catch (IamportResponseException | IOException e) {
            return -1;
        }
    }



    /*
        // 세션에서 user_id 가져오기
        private int getUserId() {
            return (int) request.getSession().getAttribute("userId");
        }
     */

    //임시 하드코딩
    private int getUserId() {
        return 2;
    }

}
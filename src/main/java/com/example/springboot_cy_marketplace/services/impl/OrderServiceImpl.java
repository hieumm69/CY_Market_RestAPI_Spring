package com.example.springboot_cy_marketplace.services.impl;

import com.example.springboot_cy_marketplace.config.VnPayConfig;
import com.example.springboot_cy_marketplace.dto.*;
import com.example.springboot_cy_marketplace.entity.*;
import com.example.springboot_cy_marketplace.model.Constant;
import com.example.springboot_cy_marketplace.repository.*;
import com.example.springboot_cy_marketplace.services.IOrderService;
import com.example.springboot_cy_marketplace.services.PayPalServices;
import com.paypal.api.payments.Links;
import com.paypal.api.payments.Payment;
import com.paypal.base.rest.PayPalRESTException;
import com.posadskiy.currencyconverter.CurrencyConverter;
import com.posadskiy.currencyconverter.config.ConfigBuilder;
import com.posadskiy.currencyconverter.enums.Currency;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements IOrderService {
    @Value("${server.host.fe.user}")
    public String homePage;
    @Autowired
    PayPalServices payPalServices;
    @Autowired
    MailService mailService;
    @Autowired
    private IOrderRepository orderRepository;
    @Autowired
    private CartServiceImpl cartService;
    @Autowired
    private IUserRepository userRepository;
    @Autowired
    private IProductClassifiedRepository productClassifiedRepository;
    @Autowired
    private INoticesLocalRepository noticesLocalRepository;
    @Autowired
    private INotifyCategoryRepository notifyCategoryRepository;
    @Value("${payment.success.paypal.url}")
    private String paypalSuccessUrl;
    @Value("${payment.success.vnpay.url}")
    private String vnpaySuccessUrl;
    @Value("${payment.failed.url}")
    private String paymentFailedUrl;
    @Value("${currency_converter_api_key}")
    private String currencyConverterApiKey;

    @Value("${open_exchange_rates_api_key}")
    private String openExchangeRatesApiKey;

    @Value("${currency_layer_api_key}")
    private String currencyLayerApiKey;

    @Autowired
    private ProductStatisticalService productStatisticalService;

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:27 CH
     * @description-VN: ?????t ????n h??ng m???i.
     * @description-EN: Place new order.
     * @param: userId - M?? ng?????i d??ng ?????t h??ng.
     * @param: paymentMethod - Ph????ng th???c thanh to??n.
     * @param: addressId - M?? ?????a ch??? giao h??ng.
     * @return:
     *
     * */
    public String placedOrder(PlaceOrderDTO placeOrder) {
        CartDTO listProductToCheckout = cartService.getListProductToCheckout(placeOrder.getUserId());
        if (listProductToCheckout == null) {
            return "Can not find list product to checkout!";
        }

        UserEntity userEntity = userRepository.findById(placeOrder.getUserId()).orElse(null);
        if (userEntity == null) {
            return null;
        }

        // S??? ti???n thanh to??n ph???i l???n h??n s??? ti???n gi???m gi??.
        if(placeOrder.getTotalDiscount() > listProductToCheckout.getTotalPrice()) {
            return "Total discount must be less than total price!";
        }

        // Gi?? v???n c???a ????n h??ng
        double costPrice = 0;
        // L???i nhu???n c???a ????n h??ng
        double profit = 0;
        List<OrderItemEntity> listOrderItemEntity = new ArrayList<>();
        // Ki???m tra s??? l?????ng ?????t mua kh??ng ???????c l???n h??n t???n kho.
        // Check quantity of order is not greater than stock.
        for (CartProductDTO item : listProductToCheckout.getProductList()) {
            ProductClassifiedEntity productClassified = productClassifiedRepository.findById(item.getProductClassifiedId())
                    .orElse(null);
            if (productClassified == null) {
                continue;
            }
            if (productClassified.getAmount() < item.getQuantity()) {
                return "Product " + productClassified.getProductEntity().getName() + " is out of stock!";
            }

            // C???p nh???t s??? l?????ng s???n ph???m b??n ra
            productStatisticalService.updateBuy(productClassified.getProductEntity().getId());

            String classified1 = productClassified.getClassifyName1();
            String classified2 = productClassified.getClassifyName2();
            OrderItemEntity orderItemEntity = new OrderItemEntity(productClassified, item.getQuantity(), item.getNewPrice(),
                     classified1 == null ? "" : classified1, classified2 == null ? "" : classified2);
            listOrderItemEntity.add(orderItemEntity);

            // T??nh l???i nhu???n
            profit += (Double.parseDouble(productClassified.getNewPrice()) * item.getQuantity()) -
                    (productClassified.getProductEntity().getCostPrice() * item.getQuantity());

            // T??nh gi?? v???n
            costPrice += productClassified.getProductEntity().getCostPrice() * item.getQuantity();
        }

        boolean isPayByPayPal = false;
        boolean isPayByVnPay = false;
        OrderEntity orderEntity = new OrderEntity(userEntity, listOrderItemEntity);
        // L??u th??ng tin ?????a ch??? nh???n h??ng
        // Save information of address to receive goods.
        orderEntity.setProvinceName(placeOrder.getProvinceName());
        orderEntity.setCityName(placeOrder.getCityName());
        orderEntity.setDistrictName(placeOrder.getDistrictName());

        // L??u l???i s??? ti???n gi???m gi?? (mi???n ph?? v???n chuy???n, m?? gi???m gi??,...)
        Double totalDiscount = placeOrder.getTotalDiscount() == null ? 0 : placeOrder.getTotalDiscount();
        orderEntity.setTotalDiscount(totalDiscount);
        orderEntity.setDiscountProduct(placeOrder.getDiscountProduct());
        orderEntity.setDiscountFreeShip(placeOrder.getDiscountFreeShip());

        // L??u l???i l???i nhu???n
        orderEntity.setProfit(profit - totalDiscount);

        // L??u l???i gi?? v???n
        orderEntity.setCostPrice(costPrice);

        String homeAddress = placeOrder.getHomeAddress().substring(0, placeOrder.getHomeAddress().indexOf(","));
        orderEntity.setHomeAddress(homeAddress);

        // L??u l???i ph????ng th???c thanh to??n c???a ????n h??ng
        if (placeOrder.getPaymentMethod().equalsIgnoreCase(Constant.ORDER_PAY_PAYPAL)) {
            isPayByPayPal = true;
            orderEntity.setStatus(Constant.ORDER_WAITING_PAYMENT);
            orderEntity.setPaymentMethod(Constant.ORDER_PAY_PAYPAL);
        } else if (placeOrder.getPaymentMethod().equalsIgnoreCase(Constant.ORDER_PAY_VNPAY)) {
            isPayByVnPay = true;
            orderEntity.setStatus(Constant.ORDER_WAITING_PAYMENT);
            orderEntity.setPaymentMethod(Constant.ORDER_PAY_VNPAY);
        } else {
            orderEntity.setStatus(Constant.ORDER_PICKING);
            orderEntity.setPaymentMethod(Constant.ORDER_PAY_COD);
        }
        orderEntity.setTotalQuantity(listProductToCheckout.getTotalQuantity());
        orderEntity.setShippingFee(placeOrder.getShippingFee());
        orderEntity.setTotalPrice(listProductToCheckout.getTotalPrice());

        // N???u kh??ch h??ng thanh to??n tr???c tuy???n
        // If customer pay online
        if (isPayByPayPal) {
            try {
                String paymentUrl = this.payByPayPal(listProductToCheckout.getTotalPrice() + placeOrder.getShippingFee() - totalDiscount);
                this.setTokenForOrder(orderEntity, paymentUrl);
                orderRepository.save(orderEntity);

                // D???n s???ch gi??? h??ng
                this.clearCartAfterOrder(placeOrder.getUserId(), listProductToCheckout);

                // Tr??? t???n kho
                this.minusProductStock(listProductToCheckout);
                return paymentUrl;
            } catch (PayPalRESTException e) {
                e.printStackTrace();
            }
        } else if (isPayByVnPay) {
            String vnPayUrl = this.payByVNPay(listProductToCheckout.getTotalPrice().longValue() +
                    placeOrder.getShippingFee().longValue() - totalDiscount.longValue());
            this.setTokenForOrder(orderEntity, vnPayUrl);
            orderRepository.save(orderEntity);

            // D???n s???ch gi??? h??ng
            this.clearCartAfterOrder(placeOrder.getUserId(), listProductToCheckout);

            // Tr??? t???n kho
            this.minusProductStock(listProductToCheckout);
            return vnPayUrl;
        }
        OrderEntity result = orderRepository.save(orderEntity);
        // N???u kh??ch h??ng thanh to??n ti???n m???t - If customer pay cash.
        // D???n s???ch gi??? h??ng - Clear cart after order.
        this.clearCartAfterOrder(placeOrder.getUserId(), listProductToCheckout);

        // Tr??? t???n kho - Subtract stock.
        this.minusProductStock(listProductToCheckout);

        // G???i mail x??c nh???n - Send mail confirm.
        this.sendMailToUser(orderEntity, Constant.ORDER_PAY_COD, null, null);

        // T???o th??ng b??o m???i cho ng?????i d??ng
        NoticesLocalEntity notice = new NoticesLocalEntity();
        notice.setTitle("Th??ng b??o t??? YD-Market");
        notice.setContent("YD-Market ???? nh???n ????n h??ng #" + orderEntity.getId());
        notice.setUserEntity(orderEntity.getUserEntity());
        notice.setNotifyCategoryEntity(this.notifyCategoryRepository.findById(3L).orElse(null));
        noticesLocalRepository.save(notice);

        // Tr??? v??? ???????ng d???n chi ti???t ????n h??ng - Return url detail order.
        return homePage + "/order/" + result.getId();
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:25 CH
     * @description-VN: K??ch ho???t ????n h??ng thanh to??n tr???c tuy???n.
     * @description-EN: Activate order payment online.
     * @param: paymentId - M?? giao d???ch thanh to??n b??n PayPal.
     * @param: token - Token thanh to??n do PayPal/VnPay t???o.
     * @param: payerId - M?? t??i kho???n thanh to??n b??n PayPal.
     * @param: vnp_ResponseCode - M?? tr???ng th??i giao d???ch VnPay g???i v??? (00 l?? th??nh c??ng).
     * @return:
     *
     * */
    public OrderDetailDTO onlinePaymentResult(PayPalResultDTO payPalResult, VnPayResultDTO vnPayResult) {
        Optional<OrderEntity> checkOrder = orderRepository.findByPaymentToken(payPalResult != null ? payPalResult.getToken() :
                vnPayResult.getVnp_TxnRef() + vnPayResult.getVnp_Amount());
        if (checkOrder.isEmpty()) {
            return null;
        }
        OrderEntity orderEntity = checkOrder.get();
        if (vnPayResult != null) {
            if (vnPayResult.getVnp_ResponseCode().equals("00")) {
                orderEntity.setStatus(Constant.ORDER_PICKING);
                orderEntity.setPaymentToken(null);

                // G???i mail x??c nh???n - Send mail confirm.
                this.sendMailToUser(orderEntity, Constant.ORDER_PAY_VNPAY, vnPayResult, null);
                orderRepository.save(orderEntity);
                return entityToDTO(orderEntity);
            }
        }
        try {
            assert payPalResult != null;
            Payment payment = payPalServices.executePayment(payPalResult.getPaymentId(), payPalResult.getPayerId());
            if (payment.getState().equals("approved")) {
                orderEntity.setStatus(Constant.ORDER_PICKING);
                orderEntity.setPaymentToken(null);
                orderRepository.save(orderEntity);
                payPalResult.setPaymentId(payment.getId());
                Double totalAmount = orderEntity.getTotalPrice() + orderEntity.getShippingFee();
                CurrencyConverter converter = new CurrencyConverter(
                        new ConfigBuilder()
                                .currencyConverterApiApiKey(currencyConverterApiKey)
                                .currencyLayerApiKey(currencyLayerApiKey)
                                .openExchangeRatesApiKey(openExchangeRatesApiKey)
                                .build()
                );
                payPalResult.setTotalAmount(totalAmount * converter.rate(Currency.VND, Currency.USD));
                // G???i mail x??c nh???n - Send mail confirm.
                this.sendMailToUser(orderEntity, Constant.ORDER_PAY_PAYPAL, null, payPalResult);
                return entityToDTO(orderEntity);
            } else {
                return null;
            }
        } catch (PayPalRESTException e) {
            e.printStackTrace();
            return null;
        }
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:24 CH
     * @description-VN: L???y danh s??ch ????n h??ng c???a ng?????i d??ng (c?? ph??n trang).
     * @description-EN: Get list order of user.
     * @param: userId - M?? ng?????i d??ng mu???n l???y danh s??ch ????n h??ng.
     * @param: pageable - Th??ng tin ph??n trang.
     * @return:
     *
     * */
    public Page<OrderDetailDTO> getOrderByUserId(Long userId, Pageable pageable) {
        Pageable pageable2 = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createDate"));
        Page<OrderEntity> orderEntities = orderRepository.findByUserEntity_Id(userId, pageable2);
        return orderEntities.map(this::entityToDTO);
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:23 CH
     * @description-VN: L???y th??ng tin chi ti???t ????n h??ng.
     * @description-EN: Get order detail.
     * @param: orderId - M?? ????n h??ng c???n l???y th??ng tin.
     * @return:
     *
     * */
    public OrderDetailDTO getOrderByOrderId(Long orderId) {
        Optional<OrderEntity> orderEntity = orderRepository.findById(orderId);
        if (orderEntity.isEmpty()) {
            return null;
        }
        return entityToDTO(orderEntity.get());
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:23 CH
     * @description-VN: Hu??? ????n h??ng.
     * @description-EN: Cancel order.
     * @param: orderId - M?? ????n h??ng mu???n hu???.
     * @return:
     *
     * */
    public boolean cancelOrder(Long orderId) {
        Optional<OrderEntity> orderEntity = orderRepository.findById(orderId);
        if (orderEntity.isEmpty()) {
            return false;
        }
        if (orderEntity.get().getStatus().equals(Constant.ORDER_SHIPPING)) {
            return false;
        }
        orderEntity.get().setStatus(Constant.ORDER_CANCEL);
        this.addProductStock(orderEntity.get());
        orderRepository.save(orderEntity.get());
        OrderEntity orderGet = orderEntity.get();
        // T???o th??ng b??o m???i cho ng?????i d??ng
        NoticesLocalEntity notice = new NoticesLocalEntity();
        notice.setTitle("Th??ng b??o t??? YD-Market");
        notice.setContent("????n h??ng #" + orderGet.getId() + " ???? b??? hu???!");
        notice.setUserEntity(orderGet.getUserEntity());
        notice.setNotifyCategoryEntity(this.notifyCategoryRepository.findById(3L).orElse(null));
        noticesLocalRepository.save(notice);
        return true;
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:22 CH
     * @description-VN: L???c danh s??ch ????n h??ng theo tr???ng th??i v?? m?? ng?????i d??ng (c?? ph??n trang).
     * @description-EN: Filter list order by status and user id (with pagination).
     * @param: userId - M?? ng?????i d??ng.
     * @param: status - Tr???ng th??i ????n h??ng.
     * @param: pageable - Ph??n trang.
     * @return:
     *
     * */
    public Page<OrderDetailDTO> filterOrderByStatus(Long userId, String status, Pageable pageable) {
        Pageable pageable2 = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createDate"));
        if (status == null || status.equalsIgnoreCase("ALL")) {
            return orderRepository.findByUserEntity_Id(userId, pageable2).map(this::entityToDTO);
        }
        return orderRepository.findByStatusAndUserEntity_Id(status, userId, pageable2).map(this::entityToDTO);
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:21 CH
     * @description-VN: T???o li??n k???t thanh to??n VNPay.
     * @description-EN: Create link payment VNPay.
     * @param: totalPrice - T???ng ti???n c???n thanh to??n (Vi???t Nam ?????ng).
     * @return:
     *
     * */
    public String payByVNPay(Long totalPrice) {
        String vnp_OrderInfo = "Thanh toan don hang tai YD-Market";
        String vnp_TxnRef = VnPayConfig.getRandomNumber(8);
        String bank_code = "NCB";

        String vnp_Version = "2.1.0";
        String vnp_Command = "pay";
        String orderType = "ATM";
        String vnp_IpAddr = "0:0:0:0:0:0:0:1";
        String vnp_TmnCode = VnPayConfig.vnp_TmnCode;
        Long amount = totalPrice * 100;
        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", vnp_Version);
        vnp_Params.put("vnp_Command", vnp_Command);
        vnp_Params.put("vnp_TmnCode", vnp_TmnCode);
        vnp_Params.put("vnp_Amount", String.valueOf(amount));
        vnp_Params.put("vnp_CurrCode", "VND");

        if (bank_code != null && !bank_code.isEmpty()) {
            vnp_Params.put("vnp_BankCode", bank_code);
        }
        vnp_Params.put("vnp_TxnRef", vnp_TxnRef);
        vnp_Params.put("vnp_OrderInfo", vnp_OrderInfo);
        vnp_Params.put("vnp_OrderType", orderType);

        String locate = "vn";
        if (locate != null && !locate.isEmpty()) {
            vnp_Params.put("vnp_Locale", locate);
        } else {
            vnp_Params.put("vnp_Locale", "vn");
        }
        vnp_Params.put("vnp_ReturnUrl", VnPayConfig.vnp_Returnurl);
        vnp_Params.put("vnp_IpAddr", vnp_IpAddr);

        Date dt = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
        String vnp_CreateDate = formatter.format(dt);
        vnp_Params.put("vnp_CreateDate", vnp_CreateDate);

        Calendar cldvnp_ExpireDate = Calendar.getInstance();
        cldvnp_ExpireDate.add(Calendar.SECOND, 30);
        Date vnp_ExpireDateD = cldvnp_ExpireDate.getTime();
        String vnp_ExpireDate = formatter.format(vnp_ExpireDateD);

        vnp_Params.put("vnp_ExpireDate", vnp_ExpireDate);

        List fieldNames = new ArrayList(vnp_Params.keySet());
        Collections.sort(fieldNames);
        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        Iterator itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = (String) itr.next();
            String fieldValue = vnp_Params.get(fieldName);
            if ((fieldValue != null) && (fieldValue.length() > 0)) {
                //Build hash data
                hashData.append(fieldName);
                hashData.append('=');
                try {
                    //hashData.append(fieldValue); //s??? d???ng v?? 2.0.0 v?? 2.0.1 checksum sha256
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString())); //s??? d???ng v2.1.0  check sum sha512
                    //Build query
                    query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString()));
                    query.append('=');
                    query.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                if (itr.hasNext()) {
                    query.append('&');
                    hashData.append('&');
                }
            }
        }
        String queryUrl = query.toString();
        String vnp_SecureHash = VnPayConfig.hmacSHA512(VnPayConfig.vnp_HashSecret, hashData.toString());
        queryUrl += "&vnp_SecureHash=" + vnp_SecureHash;
        return VnPayConfig.vnp_PayUrl + "?" + queryUrl;
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:20 CH
     * @description-VN: T???o li??n k???t thanh to??n PayPal.
     * @description-EN: Create link payment PayPal.
     * @param: totalPrice - T???ng ti???n c???n thanh to??n (Vi???t Nam ?????ng).
     * @return:
     *
     * */
    public String payByPayPal(Double totalPrice) throws PayPalRESTException {
        CurrencyConverter converter = new CurrencyConverter(
                new ConfigBuilder()
                        .currencyConverterApiApiKey(currencyConverterApiKey)
                        .currencyLayerApiKey(currencyLayerApiKey)
                        .openExchangeRatesApiKey(openExchangeRatesApiKey)
                        .build()
        );
        double vndToUSD = totalPrice * converter.rate(Currency.VND, Currency.USD);
        Payment payment = payPalServices.createPayment(vndToUSD, "USD", Constant.ORDER_PAY_PAYPAL,
                "Sale", "Payment for order at YD-Market", homePage + paymentFailedUrl,
                homePage + paypalSuccessUrl);
        for (Links link : payment.getLinks()) {
            if (link.getRel().equals("approval_url")) {
                return link.getHref();
            }
        }
        return null;
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:19 CH
     * @description-VN: Xo?? h???t s???n ph???m trong gi??? h??ng sau khi ng?????i d??ng ?????t h??ng.
     * @description-EN: Delete all product in cart after user order.
     * @param: userId - M?? ng?????i d??ng ?????t h??ng.
     * @param: items - Danh s??ch s???n ph???m ?????t h??ng.
     * @return:
     *
     * */
    public void clearCartAfterOrder(Long userId, CartDTO items) {
        for (CartProductDTO product : items.getProductList()) {
            AddToCartDTO addToCartDTO = new AddToCartDTO();
            addToCartDTO.setUserId(userId);
            addToCartDTO.setProductClassifiedId(product.getProductClassifiedId());
            addToCartDTO.setQuantity(0);
            cartService.updateCart(addToCartDTO);
        }
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:18 CH
     * @description-VN: Tr??? t???n kho s???n ph???m sau khi ng?????i d??ng ?????t h??ng.
     * @description-EN: Decrease product stock after user order.
     * @param: listProduct - Ch???a danh s??ch c??c s???n ph???m ???????c ?????t.
     * @return:
     *
     * */
    public void minusProductStock(CartDTO listProduct) {
        for (CartProductDTO item : listProduct.getProductList()) {
            Optional<ProductClassifiedEntity> productClassifiedEntity = productClassifiedRepository.findById(item.getProductClassifiedId());
            if (productClassifiedEntity.isPresent()) {
                productClassifiedEntity.get().setAmount(productClassifiedEntity.get().getAmount() - item.getQuantity());
                productClassifiedRepository.save(productClassifiedEntity.get());
            }
        }
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:17 CH
     * @description-VN: T??ng t???n kho s???n ph???m sau khi ng?????i d??ng hu??? ????n h??ng.
     * @description-EN: Increase product stock after user cancel order.
     * @param: orderEntity - ????n h??ng b??? hu???.
     * @return:
     *
     * */
    public void addProductStock(OrderEntity orderEntity) {
        List<OrderItemEntity> items = orderEntity.getItems();
        for (OrderItemEntity item : items) {
            Optional<ProductClassifiedEntity> productClassifiedEntity = productClassifiedRepository.findById(item.getItem().getId());
            if (productClassifiedEntity.isPresent()) {
                productClassifiedEntity.get().setAmount(productClassifiedEntity.get().getAmount() + item.getQuantity());
                productClassifiedRepository.save(productClassifiedEntity.get());
            }
        }
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:15 CH
     * @description-VN: T???o li??n k???t cho ng?????i d??ng thanh to??n l???i ????n h??ng.
     * @description-EN: Create link for user to pay again order.
     * @param: orderId - M?? ????n h??ng mu???n t???o l???i li??n k???t thanh to??n.
     * @return:
     *
     * */
    public String repayOrder(Long orderId) {
        String paymentLink = "";
        if (this.orderRepository.findById(orderId).isPresent()) {
            OrderEntity orderEntity = this.orderRepository.findById(orderId).get();
            if (orderEntity.getStatus().equals(Constant.ORDER_WAITING_PAYMENT)) {
                String paymentMethod = orderEntity.getPaymentMethod();
                if (paymentMethod.equals(Constant.ORDER_PAY_VNPAY)) {
                    paymentLink = this.payByVNPay(orderEntity.getTotalPrice().longValue());
                    this.setTokenForOrder(orderEntity, paymentLink);
                    this.orderRepository.save(orderEntity);
                } else if (paymentMethod.equals(Constant.ORDER_PAY_PAYPAL)) {
                    try {
                        paymentLink = this.payByPayPal(orderEntity.getTotalPrice());
                    } catch (PayPalRESTException exception) {
                        exception.printStackTrace();
                    }
                    this.setTokenForOrder(orderEntity, paymentLink);
                    this.orderRepository.save(orderEntity);
                }
            } else {
                return "This order has been paid!";
            }
        } else {
            return "Can not find order!";
        }
        return paymentLink;
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:15 CH
     * @description-VN: C??i token thanh to??n VnPay ho???c PayPal cho ????n h??ng.
     * @description-EN: Set token for VnPay or PayPal for order.
     * @param: orderEntity - ????n h??ng mu???n c??i token.
     * @param: paymentLink - Li??n k???t thanh to??n.
     * @return:
     *
     * */
    public void setTokenForOrder(OrderEntity orderEntity, String paymentUrl) {
        String token = "";
        if (orderEntity.getPaymentMethod().equals(Constant.ORDER_PAY_PAYPAL)) {
            int startIndex = paymentUrl.lastIndexOf('=');
            token = paymentUrl.substring(startIndex + 1);
        } else if (orderEntity.getPaymentMethod().equals(Constant.ORDER_PAY_VNPAY)) {
            int refStart = paymentUrl.indexOf("vnp_TxnRef");
            int refEnd = paymentUrl.indexOf("&", refStart);
            String ref = paymentUrl.substring(refStart + 11, refEnd);

            int amountStart = paymentUrl.indexOf("vnp_Amount");
            int amountEnd = paymentUrl.indexOf("&", amountStart);
            String amount = paymentUrl.substring(amountStart + 11, amountEnd);
            token = ref + amount;
        }
        orderEntity.setPaymentToken(token);
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 4:18 CH
     * @description-VN: G???i email x??c nh???n ????n h??ng v??? ng?????i d??ng.
     * @description-EN: Send email confirm order to user.
     * @param:
     * @return:
     *
     * */
    public void sendMailToUser(OrderEntity orderEntity, String paymentMethod, VnPayResultDTO vnPayResult,
                               PayPalResultDTO payPalResult) {
        MailDTO mailDTO = new MailDTO();
        mailDTO.setMailTo(orderEntity.getUserEntity().getEmail());
        mailDTO.setSubject("Thanh to??n ????n h??ng #" + orderEntity.getId() + " t???i YD-Market th??nh c??ng!");
        Map<String, Object> model = new HashMap<String, Object>();
        mailDTO.setProps(model);
        switch (paymentMethod) {
            case Constant.ORDER_PAY_VNPAY:
                model.put("transactionResult", vnPayResult.getVnp_ResponseCode().equals("00") ? "Th??nh c??ng" : "Th???t b???i");
                model.put("order", orderEntity);
                model.put("vnp_Amount", orderEntity.getTotalPrice() + orderEntity.getShippingFee());
                try {
                    mailService.customTemplateEmail(mailDTO, Constant.ORDER_PAY_VNPAY);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case Constant.ORDER_PAY_PAYPAL:
                model.put("order", orderEntity);
                model.put("payPalResult", payPalResult);
                try {
                    mailService.customTemplateEmail(mailDTO, Constant.ORDER_PAY_PAYPAL);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case Constant.ORDER_PAY_COD:
                mailDTO.setSubject("YD-Market ???? nh???n ????n h??ng #" + orderEntity.getId() + " c???a b???n!");
                model.put("order", orderEntity);
                try {
                    mailService.customTemplateEmail(mailDTO, Constant.ORDER_PAY_COD);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case Constant.ORDER_PICKING:
                mailDTO.setSubject("????n h??ng #" + orderEntity.getId() + " ??ang ???????c x??? l??!");
                model.put("order", orderEntity);
                model.put("message", "????n h??ng c???a b???n ???? ???????c ti???p nh???n v?? ??ang ???????c x??? l??!");
                try {
                    mailService.customTemplateEmail(mailDTO, Constant.ORDER_PAY_COD);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case Constant.ORDER_CANCEL:
                mailDTO.setSubject("????n h??ng #" + orderEntity.getId() + " ???? b??? hu???!");
                model.put("order", orderEntity);
                model.put("message", "YD-Market r???t ti???c khi ph???i th??ng b??o r???ng \"????n h??ng c???a b???n ???? b??? hu???!\"");
                try {
                    mailService.customTemplateEmail(mailDTO, Constant.ORDER_PAY_COD);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case Constant.ORDER_SHIPPING:
                mailDTO.setSubject("????n h??ng #" + orderEntity.getId() + " ??ang giao t???i b???n!");
                model.put("order", orderEntity);
                model.put("message", "????n h??ng c???a b???n ??ang ???????c v???n chuy???n. Th???i gian giao h??ng d??? ki???n: " +
                        LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(3).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                try {
                    mailService.customTemplateEmail(mailDTO, Constant.ORDER_PAY_COD);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case Constant.ORDER_DONE:
                mailDTO.setSubject("????n h??ng #" + orderEntity.getId() + " ???? giao th??nh c??ng!");
                model.put("order", orderEntity);
                model.put("message", "Hi v???ng s??? ???????c ph???c v??? b???n trong nh???ng ????n h??ng ti???p theo!");
                try {
                    mailService.customTemplateEmail(mailDTO, Constant.ORDER_PAY_COD);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
        }
        System.out.println("Send mail to user successfully!");
    }

    /*
     * @author: Manh Tran
     * @since: 24/06/2022 8:24 SA
     * @description-VN: L???y danh s??ch t???t c??? ????n h??ng.
     * @description-EN: Get all order.
     * @param:
     * @return:
     *
     * */

    @Override
    public Page<OrderDetailDTO> findAll(Pageable pageable) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createDate");
        Pageable pageableSort = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        return this.orderRepository.findAll(pageableSort).map(this::entityToDTO);
    }

    /*
     * @author: Manh Tran
     * @since: 24/06/2022 8:34 SA
     * @description-VN: ?????i tr???ng th??i ????n h??ng.
     * @description-EN: Change order status.
     * @param:
     * @return:
     *
     * */
    public String changeOrderStatus(Long orderId, int statusCode) {
        Optional<OrderEntity> optionalOrderEntity = this.orderRepository.findById(orderId);
        if (optionalOrderEntity.isPresent()) {
            if (optionalOrderEntity.get().getStatus().equals(Constant.ORDER_DONE)) {
                return "????n h??ng ???? giao th??nh c??ng, kh??ng th??? thay ?????i tr???ng th??i!";
            }
            OrderEntity orderEntity = optionalOrderEntity.get();
            switch (statusCode) {
                case 1:
                    orderEntity.setStatus(Constant.ORDER_PICKING);
                    break;
                case 2:
                    orderEntity.setStatus(Constant.ORDER_CANCEL);
                    break;
                case 3:
                    orderEntity.setStatus(Constant.ORDER_SHIPPING);
                    break;
                case 4:
                    orderEntity.setStatus(Constant.ORDER_DONE);
                    this.updateTotalSold(orderEntity);
                    break;
                default:
                    return "Order status code not found!";
            }
            // G???i email th??ng b??o v??? ng?????i mua
            this.sendMailToUser(orderEntity, orderEntity.getStatus(), null, null);

            // L??u l???i th??ng tin ????n h??ng
            this.orderRepository.save(orderEntity);

            // T???o th??ng b??o m???i cho ng?????i d??ng
            NoticesLocalEntity notice = new NoticesLocalEntity();
            notice.setTitle("Th??ng b??o t??? YD-Market");
            notice.setContent("????n h??ng #" + orderEntity.getId() + " " + orderEntity.getStatus().toLowerCase());
            notice.setUserEntity(orderEntity.getUserEntity());
            notice.setNotifyCategoryEntity(this.notifyCategoryRepository.findById(3L).orElse(null));
            noticesLocalRepository.save(notice);
        } else {
            return "Can not find order!";
        }
        return "Change order status successfully!";
    }

    /*
     * @author: Manh Tran
     * @since: 24/06/2022 4:50 CH
     * @description-VN: C???p nh???t s??? l?????ng s???n ph???m ???? b??n.
     * @description-EN:
     * @param:
     * @return:
     *
     * */
    public void updateTotalSold(OrderEntity orderEntity) {
        for (OrderItemEntity item : orderEntity.getItems()) {
            ProductClassifiedEntity productClassified = item.getItem();
            productClassified.setTotalSold(productClassified.getTotalSold() + item.getQuantity());
            this.productClassifiedRepository.save(productClassified);
        }
    }

    /*
     * @author: Manh Tran
     * @since: 28/06/2022 2:10 CH
     * @description-VN: T??m ????n h??ng theo t??? kho?? (nhi???u ti??u ch??).
     * @description-EN: Find order by keyword (many criteria).
     * @param: keyword - t??? kho?? t??m ki???m.
     * @param: pageable - tham s??? ph??n trang.
     * @return:
     *
     * */
    public Page<OrderDetailDTO> searchOrder(String keyword, Pageable pageable) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createDate");
        Pageable pageableSort = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        return this.orderRepository.findByKeyword(keyword, pageableSort).map(this::entityToDTO);
    }

    /*
     * @author: Manh Tran
     * @since: 28/06/2022 3:21 CH
     * @description-VN: L???c ????n h??ng theo tr???ng th??i (g???i m?? s???, kh??ng g???i chu???i).
     * @description-EN: Filter order by status (send code, not send string).
     * @param:
     * @return:
     *
     * */
    public Page<OrderDetailDTO> filterOrderByStatusAdmin(int statusCode, Pageable pageable) {
        String status = "";
        if (statusCode == 1) {
            status = Constant.ORDER_PICKING;
        } else if (statusCode == 2) {
            status = Constant.ORDER_CANCEL;
        } else if (statusCode == 3) {
            status = Constant.ORDER_SHIPPING;
        } else if (statusCode == 4) {
            status = Constant.ORDER_DONE;
        } else if (statusCode == 5) {
            status = Constant.ORDER_WAITING_PAYMENT;
        }
        return this.orderRepository.findByStatus(status, pageable).map(this::entityToDTO);
    }

    /*
     * @author: Manh Tran
     * @since: 28/06/2022 3:26 CH
     * @description-VN: L???c ????n h??ng theo kho???ng th???i gian.
     * @description-EN: Filter order by time range.
     * @param: startDate - th???i gian b???t ?????u.
     * @param: endDate - th???i gian k???t th??c.
     * @return:
     *
     * */
    public Page<OrderDetailDTO> filterOrderByTime(String startDate, String endDate, Pageable pageable) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        try {
            Date startFormat = sdf.parse(startDate);
            Date endFormat = sdf.parse(endDate);
            if(startFormat.equals(endFormat)){
                endFormat.setHours(23);
                endFormat.setMinutes(59);
                endFormat.setSeconds(59);
            }
            return this.orderRepository.findByCreateDateBetween(startFormat, endFormat, pageable).map(this::entityToDTO);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /*
     * @author: Manh Tran
     * @since: 28/06/2022 3:28 CH
     * @description-VN: L???c ????n h??ng theo ph????ng th???c thanh to??n.
     * @description-EN: Filter order by payment method.
     * @param:
     * @return:
     *
     * */
    public Page<OrderDetailDTO> filterOrderByPaymentMethod(int paymentCode, Pageable pageable) {
        String paymentMethod = "";
        if (paymentCode == 1) {
            paymentMethod = Constant.ORDER_PAY_COD;
        } else if (paymentCode == 2) {
            paymentMethod = Constant.ORDER_PAY_PAYPAL;
        } else if (paymentCode == 3) {
            paymentMethod = Constant.ORDER_PAY_VNPAY;
        }
        return this.orderRepository.findByPaymentMethod(paymentMethod, pageable).map(this::entityToDTO);
    }

    /*
     * @author: Manh Tran
     * @since: 30/06/2022 8:13 SA
     * @description-VN: Th???ng k?? chung v??? ????n h??ng.
     * @description-EN: Statistic about order.
     * @param: startDate - th???i gian b???t ?????u.
     * @param: endDate - th???i gian k???t th??c.
     * @return:
     *
     * */
    public OrderStatisticDTO statisticOrder(String startDate, String endDate) {
        Date nowDate = new Date();
        String fromDateStr = "";
        String toDateStr = "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (startDate != null) {
            try {
                fromDateStr = sdf.format(sdf.parse(startDate));
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }

        if (endDate != null) {
            try {
                toDateStr = sdf.format(sdf.parse(endDate));
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        } else {
            toDateStr = sdf.format(nowDate);
        }

        OrderStatisticDTO orderStatisticDTO = new OrderStatisticDTO();
        orderStatisticDTO.setTotalOrder(this.orderRepository.count());
        orderStatisticDTO.setTotalOrderProcessing(this.orderRepository.countByStatusAndTime(fromDateStr, toDateStr, Constant.ORDER_PICKING));
        orderStatisticDTO.setTotalOrderCancel(this.orderRepository.countByStatusAndTime(fromDateStr, toDateStr, Constant.ORDER_CANCEL));
        orderStatisticDTO.setTotalOrderShipping(this.orderRepository.countByStatusAndTime(fromDateStr, toDateStr, Constant.ORDER_SHIPPING));
        orderStatisticDTO.setTotalOrderDone(this.orderRepository.countByStatusAndTime(fromDateStr, toDateStr, Constant.ORDER_DONE));
        orderStatisticDTO.setTotalWaitingForPayment(this.orderRepository.countByStatusAndTime(fromDateStr, toDateStr, Constant.ORDER_WAITING_PAYMENT));
        orderStatisticDTO.setTotalPayPalPayment(this.orderRepository.countByPaymentMethodAndTime(fromDateStr, toDateStr, Constant.ORDER_PAY_PAYPAL));
        orderStatisticDTO.setTotalVnPayPayment(this.orderRepository.countByPaymentMethodAndTime(fromDateStr, toDateStr, Constant.ORDER_PAY_VNPAY));
        orderStatisticDTO.setTotalCodPayment(this.orderRepository.countByPaymentMethodAndTime(fromDateStr, toDateStr, Constant.ORDER_PAY_COD));
        return orderStatisticDTO;
    }

    /*
     * @author: Manh Tran
     * @since: 30/06/2022 8:01 SA
     * @description-VN: Th???ng k?? t???ng doanh thu ????n h??ng theo t???ng khung gi???.
     * @description-EN: Statistical total revenue by hour.
     * @param:
     * @return:
     *
     * */
    public List<Double> statisticalByHour(String fromDayTime, String toDayTime, int type) {
        Date nowDate = new Date();
        String fromDateStr = "";
        String toDateStr = "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        if (fromDayTime != null) {
            try {
                fromDateStr = sdf.format(sdf.parse(fromDayTime));
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }

        if (toDayTime != null) {
            try {
                toDateStr = sdf.format(sdf.parse(toDayTime));
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        } else {
            toDateStr = sdf.format(nowDate);
        }

        int nowHour = Calendar.getInstance(TimeZone.getTimeZone("Asia/Saigon")).get(Calendar.HOUR_OF_DAY);
        List<Double> result = new ArrayList<>();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String yesterdayFromStr = "";
        String yesterdayToStr = "";
        String todayFromStr = "";
        String today6AMStr = "";
        String today12AMStr = "";
        String today6PMStr = "";
        String today1159PMStr = "";
        try {
            yesterdayFromStr = dtf.format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1).withHour(0).withMinute(0).withSecond(0));
            yesterdayToStr = dtf.format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1).withHour(23).withMinute(59).withSecond(59));
            todayFromStr = dtf.format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withHour(0).withMinute(0).withSecond(0));
            today6AMStr = dtf.format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withHour(6).withMinute(0).withSecond(0));
            today12AMStr = dtf.format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withHour(12).withMinute(0).withSecond(0));
            today6PMStr = dtf.format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withHour(18).withMinute(0).withSecond(0));
            today1159PMStr = dtf.format(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withHour(23).withMinute(59).withSecond(59));
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
        switch (type) {
            case 1:
                this.statisticalRevenue(result, nowHour, yesterdayFromStr, yesterdayToStr, todayFromStr, today6AMStr,
                        today12AMStr, today6PMStr, today1159PMStr);
                break;
            case 2:
                this.statisticalProfit(result, nowHour, yesterdayFromStr, yesterdayToStr, todayFromStr, today6AMStr,
                        today12AMStr, today6PMStr, today1159PMStr);
                break;
        }
        return result;
    }

    public void statisticalRevenue(List<Double> result, int nowHour, String yesterdayFromStr, String yesterdayToStr,
                                   String todayFromStr, String today6AMStr, String today12AMStr, String today6PMStr,
                                   String today1159PMStr) {
        Double revenue0 = this.orderRepository.sumRevenueByTime(yesterdayFromStr, yesterdayToStr);
        Double revenue1 = this.orderRepository.sumRevenueByTime(todayFromStr, today6AMStr);
        Double revenue2 = this.orderRepository.sumRevenueByTime(today6AMStr, today12AMStr);
        Double revenue3 = this.orderRepository.sumRevenueByTime(today12AMStr, today6PMStr);
        Double revenue4 = this.orderRepository.sumRevenueByTime(today6PMStr, today1159PMStr);
        result.add(0, revenue0 == null ? 0 : revenue0);
        if (nowHour <= 6) {
            result.add(1, revenue1 == null ? 0 : revenue1);
        } else if (nowHour <= 12) {
            result.add(1, revenue1 == null ? 0 : revenue1);
            result.add(2, revenue2 == null ? 0 : revenue2);
        } else if (nowHour <= 18) {
            result.add(1, revenue1 == null ? 0 : revenue1);
            result.add(2, revenue2 == null ? 0 : revenue2);
            result.add(3, revenue3 == null ? 0 : revenue3);
        } else {
            result.add(1, revenue1 == null ? 0 : revenue1);
            result.add(2, revenue2 == null ? 0 : revenue2);
            result.add(3, revenue3 == null ? 0 : revenue3);
            result.add(4, revenue4 == null ? 0 : revenue4);
        }
    }

    public void statisticalProfit(List<Double> result, int nowHour, String yesterdayFromStr, String yesterdayToStr,
                                  String todayFromStr, String today6AMStr, String today12AMStr, String today6PMStr,
                                  String today1159PMStr) {
        Double profit0 = this.orderRepository.sumProfitByTime(yesterdayFromStr, yesterdayToStr);
        Double profit1 = this.orderRepository.sumProfitByTime(todayFromStr, today6AMStr);
        Double profit2 = this.orderRepository.sumProfitByTime(today6AMStr, today12AMStr);
        Double profit3 = this.orderRepository.sumProfitByTime(today12AMStr, today6PMStr);
        Double profit4 = this.orderRepository.sumProfitByTime(today6PMStr, today1159PMStr);
        result.add(0, profit0 == null ? 0 : profit0);
        if (nowHour <= 6) {
            result.add(1, profit1 == null ? 0 : profit1);
        } else if (nowHour <= 12) {
            result.add(1, profit1 == null ? 0 : profit1);
            result.add(2, profit2 == null ? 0 : profit2);
        } else if (nowHour <= 18) {
            result.add(1, profit1 == null ? 0 : profit1);
            result.add(2, profit2 == null ? 0 : profit2);
            result.add(3, profit3 == null ? 0 : profit3);
        } else {
            result.add(1, profit1 == null ? 0 : profit1);
            result.add(2, profit2 == null ? 0 : profit2);
            result.add(3, profit3 == null ? 0 : profit3);
            result.add(4, profit4 == null ? 0 : profit4);
        }
    }

    /*
     * @author: Manh Tran
     * @since: 23/06/2022 2:17 CH
     * @description-VN: Chuy???n t??? entity v??? DTO.
     * @description-EN: Convert entity to DTO.
     * @param: orderEntity - Th??ng tin ????n h??ng.
     * @return:
     *
     * */
    public OrderDetailDTO entityToDTO(OrderEntity orderEntity) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss dd/MM/yyyy");
        String address = orderEntity.getHomeAddress() + ", "
                + orderEntity.getDistrictName() + ", "
                + orderEntity.getCityName() + ", "
                + orderEntity.getProvinceName();
        return OrderDetailDTO.builder()
                .orderId(orderEntity.getId())
                .userId(orderEntity.getUserEntity().getId())
                .status(orderEntity.getStatus())
                .createdAt(sdf.format(orderEntity.getCreateDate()))
                .totalPrice(orderEntity.getTotalPrice())
                .shippingFee(orderEntity.getShippingFee())
                .totalDiscount(orderEntity.getTotalDiscount())
                .paymentMethod(orderEntity.getPaymentMethod())
                .currency(orderEntity.getPaymentMethod().equals(Constant.ORDER_PAY_PAYPAL) ? "USD" : "VND")
                .intent("Mua h??ng")
                .totalPayment(orderEntity.getTotalPrice() + orderEntity.getShippingFee() - orderEntity.getTotalDiscount())
                .discountProduct(orderEntity.getDiscountProduct() == null ? 0 : orderEntity.getDiscountProduct())
                .discountFreeShip(orderEntity.getDiscountFreeShip() == null ? 0 : orderEntity.getDiscountFreeShip())
                .receiverName(orderEntity.getUserEntity().getFullName())
                .receiverEmail(orderEntity.getUserEntity().getEmail())
                .receiverPhone(orderEntity.getUserEntity().getPhoneNumber())
                .receiverAddress(address)
                .productList(orderEntity.getItems().stream().map(orderItemEntity -> {
                    ProductClassifiedEntity productClassifiedEntity = orderItemEntity.getItem();
                    return CartProductDTO.builder()
                            .productId(productClassifiedEntity.getProductEntity().getId())
                            .productName(productClassifiedEntity.getProductEntity().getName())
                            .productCoverImage(productClassifiedEntity.getProductEntity().getCoverImage())
                            .productClassifiedId(productClassifiedEntity.getId())
                            .productClassifiedBy01(productClassifiedEntity.getProductEntity().getClassifiedBy01())
                            .productClassifiedName1(orderItemEntity.getClassified1())
                            .productClassifiedBy02(productClassifiedEntity.getProductEntity().getClassifiedBy02())
                            .productClassifiedName2(orderItemEntity.getClassified2())
                            .classifiedImage(productClassifiedEntity.getImage())
                            .newPrice(orderItemEntity.getPrice())
                            .oldPrice(Double.parseDouble(productClassifiedEntity.getOldPrice()))
                            .discount(productClassifiedEntity.getDiscount())
                            .amount(productClassifiedEntity.getAmount())
                            .quantity(orderItemEntity.getQuantity())
                            .height(productClassifiedEntity.getProductEntity().getHeight())
                            .width(productClassifiedEntity.getProductEntity().getWidth())
                            .length(productClassifiedEntity.getProductEntity().getLength())
                            .weight(productClassifiedEntity.getProductEntity().getWeight())
                            .build();
                }).collect(Collectors.toList()))
                .totalQuantity(orderEntity.getTotalQuantity())
                .description("Thanh to??n ????n h??ng t???i YD-Market!")
                .reviewStatus(orderEntity.isReviewStatus())
                .build();
    }

    @Override
    public List<OrderDetailDTO> findAll() {
        return null;
    }

    @Override
    public OrderDetailDTO findById(Long id) {
        return null;
    }

    @Override
    public OrderDetailDTO add(OrderEntity dto) {

        return null;
    }

    @Override
    public List<OrderDetailDTO> add(List<OrderEntity> dto) {
        return null;
    }

    @Override
    public OrderDetailDTO update(OrderEntity dto) {
        return null;
    }

    @Override
    public boolean deleteById(Long id) {
        return false;
    }

    @Override
    public boolean deleteByIds(List<Long> id) {
        return false;
    }

}

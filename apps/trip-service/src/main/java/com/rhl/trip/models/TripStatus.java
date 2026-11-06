package com.rhl.trip.models;

/**
 * Exact 9 Trip Lifecycle States defined in SRS-RHL-002 (Section 4.6.1 & Section 7.1).
 */
public enum TripStatus {
    /**
     * Yeu cau chuyen da duoc tao (khoi tao tu FareQuote con hieu luc).
     */
    CREATED,

    /**
     * Dang tim va gui offer cho tai xe phu hop.
     */
    MATCHING,

    /**
     * Mot tai xe da chap nhan offer nguyen tu.
     */
    ACCEPTED,

    /**
     * Tai xe dang di chuyen toi diem don/lay hang.
     */
    PICKING_UP,

    /**
     * Tai xe da toi diem don/lay hang va xac nhan den.
     */
    ARRIVED,

    /**
     * Khach/hang da duoc nhan len xe; chuyen dang duoc thuc hien.
     */
    IN_TRIP,

    /**
     * Van chuyen da hoan tat thanh cong tai diem den (Terminal State).
     */
    COMPLETED,

    /**
     * Chuyen bi huy boi khach hang, tai xe hoac nhan vien ho tro (Terminal State).
     */
    CANCELLED,

    /**
     * Khong tim duoc tai xe trong gioi han matching (Terminal State).
     */
    NO_DRIVER;

    /**
     * Kiem tra xem trang thai co phai la trang thai ket thuc (Terminal) hay khong.
     * Cac trang thai ket thuc khong the chuyen sang bat ky trang thai nao khac.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == NO_DRIVER;
    }

    /**
     * Kiem tra xem cuoc xe co dang hoat dong (Active) hay khong.
     * Dung de kiem soat gioi han mot khach hang chi co toi da mot chuyen hoat dong (FR-TRIP-007).
     */
    public boolean isActive() {
        return !isTerminal();
    }
}

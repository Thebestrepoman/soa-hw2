package ru.marketplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marketplace.model.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class PromoService {
    private final JdbcTemplate db;
    public PromoService(JdbcTemplate db) { this.db = db; }

    @Transactional
    public PromoCodeResponse create(PromoCodeCreate r) {
        Actor.current().allow("SELLER", "ADMIN");
        UUID id = UUID.randomUUID();
        int created = db.update("""
                INSERT INTO promo_codes(id,code,discount_type,discount_value,min_order_amount,max_uses,valid_from,valid_until,active)
                VALUES (?,?,?::discount_type,?,?,?,?,?,?) ON CONFLICT(code) DO NOTHING
                """, id, r.getCode(), r.getDiscountType().getValue(), r.getDiscountValue(), r.getMinOrderAmount(),
                r.getMaxUses(), r.getValidFrom(), r.getValidUntil(), r.getActive() == null || r.getActive());
        if (created == 0) throw new BusinessException(409, "PROMO_CODE_EXISTS", "Promo code already exists");
        return byId(id);
    }

    PromoCodeResponse byCode(String code) {
        var rows = db.query("SELECT * FROM promo_codes WHERE code=? FOR UPDATE", this::map, code);
        if (rows.isEmpty()) throw invalid();
        return rows.getFirst();
    }

    PromoCodeResponse byId(UUID id) {
        return db.queryForObject("SELECT * FROM promo_codes WHERE id=? FOR UPDATE", this::map, id);
    }

    void validate(PromoCodeResponse promo, boolean alreadyUsed) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        int otherUses = promo.getCurrentUses() - (alreadyUsed ? 1 : 0);
        if (!promo.getActive() || otherUses >= promo.getMaxUses() || now.isBefore(promo.getValidFrom()) || now.isAfter(promo.getValidUntil()))
            throw invalid();
    }

    BigDecimal discount(PromoCodeResponse promo, BigDecimal subtotal) {
        if (promo.getDiscountType() == DiscountType.FIXED_AMOUNT) return promo.getDiscountValue().min(subtotal);
        // Assignment: percentage exceeding 70% means full price, not a capped discount.
        if (promo.getDiscountValue().compareTo(new BigDecimal("70")) > 0) return BigDecimal.ZERO.setScale(2);
        return subtotal.multiply(promo.getDiscountValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    void usage(UUID id, int delta) {
        db.update("UPDATE promo_codes SET current_uses=current_uses+? WHERE id=?", delta, id);
    }

    private BusinessException invalid() { return new BusinessException(422, "PROMO_CODE_INVALID", "Promo code missing, inactive, expired, or exhausted"); }

    private PromoCodeResponse map(ResultSet rs, int row) throws SQLException {
        return new PromoCodeResponse().id(rs.getObject("id", UUID.class)).code(rs.getString("code"))
                .discountType(DiscountType.fromValue(rs.getString("discount_type"))).discountValue(rs.getBigDecimal("discount_value"))
                .minOrderAmount(rs.getBigDecimal("min_order_amount")).maxUses(rs.getInt("max_uses")).currentUses(rs.getInt("current_uses"))
                .validFrom(rs.getObject("valid_from", OffsetDateTime.class)).validUntil(rs.getObject("valid_until", OffsetDateTime.class)).active(rs.getBoolean("active"));
    }
}

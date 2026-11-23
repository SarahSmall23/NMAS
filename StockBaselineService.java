package za.co.nmas.nmas.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import za.co.nmas.nmas.entity.DispensaryOrder;
import za.co.nmas.nmas.entity.MedicationInventory;
import za.co.nmas.nmas.repository.DispensaryOrderRepository;
import za.co.nmas.nmas.repository.MedicationInventoryRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * One-time clean-up for stock that was copied from the old shared pharmacy:
 * those copies inherited whatever the old shared shelf had been drawn down
 * to, so quiet facilities showed "low stock" without ever dispensing a pill.
 *
 * Every shelf row gets a full opening stock; then only what THIS facility
 * actually dispensed (from its own dispensary orders) is subtracted. A row
 * is processed once (baselineApplied) so later restocks/dispensing are
 * never overwritten.
 */
@Service
@Order(130)
public class StockBaselineService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockBaselineService.class);

    private final MedicationInventoryRepository inventoryRepository;
    private final DispensaryOrderRepository orderRepository;

    public StockBaselineService(MedicationInventoryRepository inventoryRepository,
                                DispensaryOrderRepository orderRepository) {
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Map<Long, Integer> dispensedByRow = new HashMap<>();
            for (DispensaryOrder o : orderRepository.findAll()) {
                if (!"DISPENSED".equals(o.getStatus()) || o.getInventoryItem() == null || o.getDispensedPacks() == null) continue;
                dispensedByRow.merge(o.getInventoryItem().getId(), o.getDispensedPacks(), Integer::sum);
            }
            int fixed = 0;
            for (MedicationInventory row : inventoryRepository.findAll()) {
                if (Boolean.TRUE.equals(row.getBaselineApplied())) continue;
                int reorder = Math.max(row.getReorderLevel(), 3);
                int full = Math.max(reorder * 4, 20);
                int dispensed = dispensedByRow.getOrDefault(row.getId(), 0);
                int target = Math.max(0, full - dispensed);
                if (row.getQuantityInStock() < target) {
                    row.setQuantityInStock(target);
                    fixed++;
                }
                row.setBaselineApplied(true);
                inventoryRepository.save(row);
            }
            if (fixed > 0) log.info("[NMAS] Stock baseline applied: {} shelf rows restored to full opening stock", fixed);
        } catch (Exception e) {
            log.warn("Stock baseline skipped: {}", e.toString());
        }
    }
}

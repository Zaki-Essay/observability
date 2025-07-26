package com.gaga.observability.repository;

import com.gaga.observability.entities.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @author mohamedyoussfi
 **/
public interface ProductRepository extends JpaRepository<Product, Long> {
}

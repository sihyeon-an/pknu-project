package com.example.pknu_backend.Controller;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.servlet.http.HttpServletResponse; // login1 API에서 사용
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders; // 현재 코드에서 직접 사용되지 않지만, 다른 HTTP 응답에 유용
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType; // 현재 코드에서 직접 사용되지 않지만, 다른 HTTP 응답에 유용
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct; // @PostConstruct 어노테이션 사용을 위한 import

import java.io.File; // Files.deleteIfExists 에서 사용될 수 있지만, 현재 직접적인 File 객체 생성은 없음
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp; // uploadImage에서 사용
import java.time.LocalDate; // lost_found_items 관련 API에서 사용
import java.time.LocalDateTime; // lost_found_items 관련 API에서 사용 (현재는 명시적으로 사용 안됨, 나중에 필요할수도)
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID; // 이미지 파일명 생성에 사용

@RestController
@Slf4j // Lombok 로깅 어노테이션
public class CommonController {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${server.port}")
    private String serverPort;

    public CommonController() {
    }

    // 애플리케이션 시작 시, 이미지 저장 폴더 및 DB 테이블 생성
    // @PostConstruct 어노테이션을 사용하여 스프링 애플리케이션 초기화 시 1회 실행 보장
    @PostConstruct
    @Transactional // DDL (CREATE TABLE) 작업도 트랜잭션으로 묶습니다.
    public void createTablesAndUploadDirIfNotExists() {
        try {
            // 1. 이미지 업로드 디렉토리 생성
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                log.info("Upload directory created: {}", uploadPath.toAbsolutePath());
            } else {
                log.info("Upload directory already exists: {}", uploadPath.toAbsolutePath());
            }

            // 2. basemp_image 테이블 생성 (기존 코드 유지)
            String createBasempImageTableSql = "CREATE TABLE IF NOT EXISTS `basemp_image` (" +
                    "`empnum` VARCHAR(10) NOT NULL," +
                    "`image_id` INT AUTO_INCREMENT PRIMARY KEY," +
                    "`image_url` VARCHAR(255)," +
                    "`upload_date` DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (`empnum`) REFERENCES `basemp`(`empnum`) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;";
            entityManager.createNativeQuery(createBasempImageTableSql).executeUpdate();
            log.info("Table 'basemp_image' created or already exists.");

            // 3. lost_found_items 테이블 생성 (분실물 센터 핵심 테이블)
            String createLostFoundItemsTableSql = "CREATE TABLE IF NOT EXISTS `lost_found_items` (" +
                    "`item_id` INT AUTO_INCREMENT PRIMARY KEY," +
                    "`item_type` ENUM('LOST', 'FOUND') NOT NULL," +
                    "`title` VARCHAR(255) NOT NULL," +
                    "`description` TEXT," +
                    "`location` VARCHAR(255)," +
                    "`item_date` DATE," +
                    "`contact_info` VARCHAR(255)," +
                    "`image_url` VARCHAR(255)," +
                    "`status` ENUM('ACTIVE', 'RESOLVED') DEFAULT 'ACTIVE'," +
                    "`posted_by_userid` VARCHAR(10) NOT NULL," +
                    "`posted_at` DATETIME DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY (`posted_by_userid`) REFERENCES `basemp`(`empnum`) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;";
            entityManager.createNativeQuery(createLostFoundItemsTableSql).executeUpdate();
            log.info("Table 'lost_found_items' created or already exists.");

        } catch (Exception e) {
            log.error("Error creating tables or upload directory: {}", e.getMessage());
        }
    }

    // 이미지를 업로드하는 API (파일 시스템에 저장 후 URL을 반환)
    @PostMapping("/api/upload")
    @Transactional
    public ResponseEntity<?> uploadImage(@RequestParam("image") MultipartFile file,
                                         @RequestParam("userid") String userId) {
        // createTablesAndUploadDirIfNotExists(); // @PostConstruct로 옮겼으므로 여기서 호출할 필요 없음

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("업로드할 파일이 없습니다.");
        }

        try {
            // 사용자 ID 유효성 검사 (basemp 테이블에 존재하는지 확인)
            String checkUserSql = "SELECT COUNT(*) FROM basemp WHERE empnum = ?";
            Query checkUserQuery = entityManager.createNativeQuery(checkUserSql);
            checkUserQuery.setParameter(1, userId);
            Long userCount = ((Number) checkUserQuery.getSingleResult()).longValue();

            if (userCount == 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("존재하지 않는 사용자 ID입니다.");
            }

            // 고유한 파일명 생성
            String originalFilename = file.getOriginalFilename();
            String fileExtension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String uniqueFileName = UUID.randomUUID().toString() + fileExtension;
            Path filePath = Paths.get(uploadDir, uniqueFileName);

            // 파일 시스템에 파일 저장
            Files.copy(file.getInputStream(), filePath);
            log.info("File saved to: {}", filePath.toAbsolutePath());

            // 프론트로 반환할 이미지 URL (상대 경로)
            String imageUrl = "/uploads/" + uniqueFileName;

            Map<String, Object> response = new HashMap<>();
            response.put("message", "이미지 업로드 성공!");
            response.put("imageUrl", imageUrl); // 업로드된 이미지의 상대 URL 반환

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("이미지 파일 저장 또는 처리 중 오류 발생:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("이미지 처리 오류: " + e.getMessage());
        } catch (Exception e) {
            log.error("이미지 업로드 중 DB 사용자 확인 오류 또는 기타 오류 발생:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("이미지 업로드 서버 오류: " + e.getMessage());
        }
    }

    // --- 분실물/습득물 게시물 관련 API들 ---

    // 1. 모든 분실물/습득물 게시물 조회
    @GetMapping("/api/items")
    public ResponseEntity<List<Map<String, Object>>> getAllItems() {
        String sql = "SELECT item_id, item_type, title, description, location, item_date, contact_info, image_url, status, posted_by_userid, posted_at " +
                "FROM lost_found_items ORDER BY posted_at DESC";
        Query query = entityManager.createNativeQuery(sql);

        List<Object[]> results = query.getResultList();
        List<Map<String, Object>> itemList = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, Object> item = new HashMap<>();
            item.put("itemId", row[0]);
            item.put("itemType", row[1]);
            item.put("title", row[2]);
            item.put("description", row[3]);
            item.put("location", row[4]);
            item.put("itemDate", row[5]); // java.sql.Date 또는 String
            item.put("contactInfo", row[6]);
            item.put("imageUrl", row[7]);
            item.put("status", row[8]);
            item.put("postedByUserId", row[9]);
            item.put("postedAt", row[10]); // java.sql.Timestamp 또는 String

            // 이미지 URL이 있다면 완전한 URL 생성 (프론트에서 직접 접근 가능한 경로)
            if (row[7] != null && !((String) row[7]).isEmpty()) {
                String fullImageUrl = "http://localhost:" + serverPort + item.get("imageUrl");
                item.put("fullImageUrl", fullImageUrl);
            } else {
                item.put("fullImageUrl", null);
            }

            itemList.add(item);
        }
        log.info("분실물/습득물 목록 조회 성공: {} 건", itemList.size());
        return ResponseEntity.ok(itemList);
    }

    // 2. 새로운 분실물/습득물 게시물 등록
    @PostMapping("/api/items")
    @Transactional
    public ResponseEntity<?> createItem(@RequestBody Map<String, String> itemData) {
        String itemType = itemData.get("itemType"); // 'LOST' 또는 'FOUND'
        String title = itemData.get("title");
        String description = itemData.get("description");
        String location = itemData.get("location");
        String itemDateStr = itemData.get("itemDate"); // "YYYY-MM-DD" 형식
        String contactInfo = itemData.get("contactInfo");
        String imageUrl = itemData.get("imageUrl"); // 이미지 업로드 후 받은 URL
        String postedByUserId = itemData.get("postedByUserId"); // 로그인한 사용자 ID

        // 필수 필드 유효성 검사
        if (itemType == null || title == null || postedByUserId == null || itemDateStr == null || contactInfo == null) {
            return ResponseEntity.badRequest().body("필수 필드(유형, 제목, 날짜, 연락처, 작성자)가 누락되었습니다.");
        }

        LocalDate itemDate = null;
        try {
            itemDate = LocalDate.parse(itemDateStr); // String to LocalDate
        } catch (Exception e) {
            log.error("날짜 파싱 오류: {}", itemDateStr, e);
            return ResponseEntity.badRequest().body("날짜 형식이 올바르지 않습니다. (YYYY-MM-DD)");
        }

        try {
            String insertSql = "INSERT INTO lost_found_items (item_type, title, description, location, item_date, contact_info, image_url, posted_by_userid) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            Query query = entityManager.createNativeQuery(insertSql);
            query.setParameter(1, itemType);
            query.setParameter(2, title);
            query.setParameter(3, description);
            query.setParameter(4, location);
            query.setParameter(5, itemDate); // LocalDate 객체 전달
            query.setParameter(6, contactInfo);
            query.setParameter(7, imageUrl);
            query.setParameter(8, postedByUserId);

            query.executeUpdate(); // DB에 반영

            log.info("게시물 등록 성공: {}", title);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("게시물 등록 실패:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: 게시물 등록 실패 - " + e.getMessage());
        }
    }

    // 3. 분실물/습득물 게시물 삭제
    @DeleteMapping("/api/items/{itemId}")
    @Transactional
    public ResponseEntity<?> deleteItem(@PathVariable Long itemId, @RequestParam("postedByUserId") String postedByUserId) {
        try {
            // 해당 게시물이 존재하는지, 그리고 삭제 요청한 사용자가 작성자인지 확인 (보안 강화)
            String checkSql = "SELECT posted_by_userid, image_url FROM lost_found_items WHERE item_id = ?";
            Query checkQuery = entityManager.createNativeQuery(checkSql);
            checkQuery.setParameter(1, itemId);
            Object[] result;
            try {
                result = (Object[]) checkQuery.getSingleResult();
            } catch (NoResultException e) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("해당 게시물을 찾을 수 없습니다.");
            }

            String actualPostedByUserId = (String) result[0];
            String imageUrlToDelete = (String) result[1];

            if (!actualPostedByUserId.equals(postedByUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("게시물 삭제 권한이 없습니다.");
            }

            String deleteSql = "DELETE FROM lost_found_items WHERE item_id = ?";
            Query query = entityManager.createNativeQuery(deleteSql);
            query.setParameter(1, itemId);

            int deletedCount = query.executeUpdate();

            if (deletedCount > 0) {
                // 게시물에 연결된 이미지 파일도 삭제 (선택 사항)
                if (imageUrlToDelete != null && !imageUrlToDelete.isEmpty()) {
                    try {
                        Path imagePath = Paths.get(uploadDir, imageUrlToDelete.substring(imageUrlToDelete.lastIndexOf("/") + 1));
                        Files.deleteIfExists(imagePath);
                        log.info("삭제된 이미지 파일: {}", imagePath.toAbsolutePath());
                    } catch (IOException e) {
                        log.warn("이미지 파일 삭제 실패 (경고): {}", e.getMessage());
                    }
                }
                log.info("게시물 삭제 성공: ID {}", itemId);
                return ResponseEntity.ok("OK");
            } else {
                log.warn("게시물 삭제 실패: ID {} (찾을 수 없음)", itemId);
                return ResponseEntity.ok("NOT_FOUND");
            }
        } catch (Exception e) {
            log.error("게시물 삭제 중 오류 발생:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: 게시물 삭제 실패 - " + e.getMessage());
        }
    }

    // 4. 분실물/습득물 게시물 수정
    @PutMapping("/api/items/{itemId}")
    @Transactional
    public ResponseEntity<?> updateItem(@PathVariable Long itemId, @RequestBody Map<String, String> itemData) {
        String itemType = itemData.get("itemType");
        String title = itemData.get("title");
        String description = itemData.get("description");
        String location = itemData.get("location");
        String itemDateStr = itemData.get("itemDate");
        String contactInfo = itemData.get("contactInfo");
        String imageUrl = itemData.get("imageUrl");
        String status = itemData.get("status"); // 'ACTIVE' 또는 'RESOLVED'
        String postedByUserId = itemData.get("postedByUserId"); // 수정 요청 사용자 ID

        // 필수 필드 유효성 검사
        if (itemType == null || title == null || postedByUserId == null || status == null || itemDateStr == null || contactInfo == null) {
            return ResponseEntity.badRequest().body("필수 필드(유형, 제목, 날짜, 연락처, 작성자, 상태)가 누락되었습니다.");
        }

        LocalDate itemDate = null;
        try {
            itemDate = LocalDate.parse(itemDateStr);
        } catch (Exception e) {
            log.error("날짜 파싱 오류: {}", itemDateStr, e);
            return ResponseEntity.badRequest().body("날짜 형식이 올바르지 않습니다. (YYYY-MM-DD)");
        }

        try {
            // 수정 요청 사용자가 작성자인지 확인
            String checkSql = "SELECT posted_by_userid FROM lost_found_items WHERE item_id = ?";
            Query checkQuery = entityManager.createNativeQuery(checkSql);
            checkQuery.setParameter(1, itemId);
            String actualPostedByUserId;
            try {
                actualPostedByUserId = (String) checkQuery.getSingleResult();
            } catch (NoResultException e) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("해당 게시물을 찾을 수 없습니다.");
            }

            if (!actualPostedByUserId.equals(postedByUserId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("게시물 수정 권한이 없습니다.");
            }

            String updateSql = "UPDATE lost_found_items SET item_type = ?, title = ?, description = ?, location = ?, item_date = ?, contact_info = ?, image_url = ?, status = ? WHERE item_id = ?";
            Query query = entityManager.createNativeQuery(updateSql);
            query.setParameter(1, itemType);
            query.setParameter(2, title);
            query.setParameter(3, description);
            query.setParameter(4, location);
            query.setParameter(5, itemDate);
            query.setParameter(6, contactInfo);
            query.setParameter(7, imageUrl);
            query.setParameter(8, status);
            query.setParameter(9, itemId);

            int updatedCount = query.executeUpdate();

            if (updatedCount > 0) {
                log.info("게시물 수정 성공: ID {}", itemId);
                return ResponseEntity.ok("OK");
            } else {
                log.warn("게시물 수정 실패: ID {} (찾을 수 없음)", itemId);
                return ResponseEntity.ok("NOT_FOUND");
            }
        } catch (Exception e) {
            log.error("게시물 수정 중 오류 발생:", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("ERROR: 게시물 수정 실패 - " + e.getMessage());
        }
    }

    // 기존 로그인, 조회, 등록, 수정, 삭제 API들은 여기에 이어서 작성
    @PostMapping("/api/login1")
    public ResponseEntity login1(@RequestBody Map<String, String> params, HttpServletResponse res) {
        // ... (기존 로그인 로직)
        String userid = params.get("s_userid");
        String userpass = params.get("s_userpass");
        log.info("아이디: " + userid + ", 비밀번호: " + userpass);
        String sql = "SELECT * FROM basemp WHERE empnum = ? AND emppas = ?";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter(1, userid);
            query.setParameter(2, userpass);
            List<Object[]> results = query.getResultList();
            log.info("results: {}", results);
            if (!results.isEmpty()) {
                Object[] row = results.get(0);
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("userid", row[0]);
                resultMap.put("userpass", row[1]);
                resultMap.put("username", row[2]);
                resultMap.put("usermail", row[3]);
                log.info("Employee Info: {}", resultMap);
                return ResponseEntity.ok(resultMap);
            } else {
                return ResponseEntity.ok("NOT");
            }
        } catch (Exception e) {
            log.error("Login error: ", e);
            return ResponseEntity.internalServerError().body("Exception: Login failed");
        }
    }
    @PostMapping("/api/login7")
    public ResponseEntity<?> login7(@RequestBody Map<String, String> params) {
        // ... (기존 login7 로직)
        String server_username = params.get("s_username");
        log.info("넘어온 검색이름: {}", server_username);
        String sql = "SELECT * FROM basemp WHERE empnam LIKE ?";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter(1, server_username + '%');
            List<Object[]> results = query.getResultList();
            log.info("results: {}", results);
            if (!results.isEmpty()) {
                List<Map<String, Object>> resultList = new ArrayList<>();
                for (Object[] row : results) {
                    Map<String, Object> resultMap = new HashMap<>();
                    resultMap.put("userid", row[0]);
                    resultMap.put("userpass", row[1]);
                    resultMap.put("username", row[2]);
                    resultMap.put("usermail", row[3]);
                    resultList.add(resultMap);
                }
                log.info("조회 결과: {}", resultList);
                return ResponseEntity.ok(resultList);
            } else {
                return ResponseEntity.ok(Map.of("status", "NOT_FOUND"));
            }
        } catch (Exception e) {
            log.error("Login error: ", e);
            return ResponseEntity.internalServerError().body("Exception Login failed");
        }
    }
    @PostMapping("/api/register")
    @Transactional
    public ResponseEntity<?> registerUser(@RequestBody Map<String, String> params) {
        // ... (기존 register 로직)
        String userid = params.get("userid");
        String userpass = params.get("userpass");
        String username = params.get("username");
        String usermail = params.get("usermail");
        log.info("회원가입 요청: {}, {}, {}, {}", userid, userpass, username, usermail);
        String sql = "INSERT INTO basemp (empnum, emppas, empnam, empmal) VALUES (?, ?, ?, ?)";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter(1, userid);
            query.setParameter(2, userpass);
            query.setParameter(3, username);
            query.setParameter(4, usermail);
            query.executeUpdate();
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("회원 등록 실패:", e);
            return ResponseEntity.internalServerError().body("ERROR: " + e.getMessage());
        }
    }
    @PostMapping("/api/update")
    @Transactional
    public ResponseEntity<?> updateUser(@RequestBody Map<String, String> params) {
        // ... (기존 update 로직)
        String userid = params.get("userid");
        String userpass = params.get("userpass");
        String username = params.get("username");
        String usermail = params.get("usermail");
        String sql = "UPDATE basemp SET emppas = ?, empnam = ?, empmal = ? WHERE empnum = ?";
        try {
            Query query = entityManager.createNativeQuery(sql);
            query.setParameter(1, userpass);
            query.setParameter(2, username);
            query.setParameter(3, usermail);
            query.setParameter(4, userid);
            int updatedCount = query.executeUpdate();
            if (updatedCount > 0) {
                return ResponseEntity.ok("OK");
            } else {
                return ResponseEntity.ok("NOT_FOUND");
            }
        } catch (Exception e) {
            log.error("업데이트 실패:", e);
            return ResponseEntity.internalServerError().body("ERROR: " + e.getMessage());
        }
    }
    @PostMapping("/api/deleteMultiple")
    @Transactional
    public ResponseEntity<?> deleteMultipleUsers(@RequestBody Map<String, List<String>> params) {
        // ... (기존 deleteMultiple 로직)
        List<String> userids = params.get("userids");
        log.info("다중 삭제 요청 받은 userids: {}", userids);
        if (userids == null || userids.isEmpty()) {
            return ResponseEntity.badRequest().body("userids가 비어있음");
        }
        String placeholders = String.join(",", userids.stream().map(id -> "?").toArray(String[]::new));
        String sql = "DELETE FROM basemp WHERE empnum IN (" + placeholders + ")";
        try {
            Query query = entityManager.createNativeQuery(sql);
            for (int i = 0; i < userids.size(); i++) {
                query.setParameter(i + 1, userids.get(i));
            }
            int deletedCount = query.executeUpdate();
            log.info("삭제된 행 수: {}", deletedCount);
            if (deletedCount > 0) {
                return ResponseEntity.ok("OK");
            } else {
                return ResponseEntity.ok("NOT_FOUND");
            }
        } catch (Exception e) {
            log.error("다중 삭제 실패:", e);
            return ResponseEntity.internalServerError().body("ERROR: " + e.getMessage());
        }
    }
}
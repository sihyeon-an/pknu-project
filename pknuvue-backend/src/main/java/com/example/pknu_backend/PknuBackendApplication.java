package com.example.pknu_backend;

import com.example.pknu_backend.Controller.CommonController; // CommonController 임포트 확인
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext; // ApplicationContext 임포트 (만약 사용한다면)

@SpringBootApplication
public class PknuBackendApplication {

	public static void main(String[] args) {
		ApplicationContext context = SpringApplication.run(PknuBackendApplication.class, args);

		// ⭐ 이 부분을 찾아서 삭제하거나 주석 처리합니다.
		// CommonController commonController = context.getBean(CommonController.class);
		// commonController.createTableAndUploadDirIfNotExists(); // 이 줄을 삭제 또는 주석 처리!
	}

}
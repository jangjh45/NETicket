package com.example.neticket.reservation.service;

import com.example.neticket.event.dto.DetailEventResponseDto;
import com.example.neticket.event.dto.MessageResponseDto;
import com.example.neticket.event.entity.TicketInfo;
import com.example.neticket.event.repository.TicketInfoRepository;
import com.example.neticket.exception.CustomException;
import com.example.neticket.exception.ExceptionType;
import com.example.neticket.reservation.dto.ReservationRequestDto;
import com.example.neticket.reservation.dto.ReservationResponseDto;
import com.example.neticket.reservation.entity.Reservation;
import com.example.neticket.reservation.repository.RedisRepository;
import com.example.neticket.reservation.repository.ReservationRepository;
import com.example.neticket.user.entity.User;
import com.example.neticket.user.entity.UserRoleEnum;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

  private final ReservationRepository reservationRepository;
  private final TicketInfoRepository ticketInfoRepository;
  private final RedisRepository redisRepository;

  // 1. 예매중 페이지에서 공연정보 조회
  @Cacheable(value = "DetailEventResponseDto", key = "#ticketInfoId", cacheManager = "cacheManager")
  @Transactional(readOnly = true)
  public DetailEventResponseDto verifyReservation(Long ticketInfoId) {
    return new DetailEventResponseDto(checkTicketInfoById(ticketInfoId).getEvent());
  }

  // 2. 예매하기
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Long makeReservation(ReservationRequestDto dto, User user) {
    //    먼저 redis 캐시를 조회
    Integer leftSeats = redisRepository.findLeftSeatsFromRedis(dto.getTicketInfoId());
    if (leftSeats == null) {
    //    캐시가 없으면 DB를 통해 남은 좌석 수 차감
      decrementLeftSeatInDB(dto);
    } else {
    //    캐시가 있으면 redis에서 남은 좌석 수 차감
      decrementLeftSeatInRedis(dto, leftSeats);
    }

    Reservation reservation = new Reservation(dto, user);
    reservationRepository.saveAndFlush(reservation);
    return reservation.getId();
  }

  //  2-1. redis로 좌석 수 변경
  private void decrementLeftSeatInRedis(ReservationRequestDto dto, Integer leftSeats) {
    if (leftSeats - dto.getCount() < 0) {
      throw new CustomException(ExceptionType.OUT_OF_TICKET_EXCEPTION);
    }
    redisRepository.decrementLeftSeatInRedis(dto.getTicketInfoId(), dto.getCount());
  }

  // 2-2. 캐시 없으면 DB로 좌석수 변경
  private void decrementLeftSeatInDB(ReservationRequestDto dto) {
    TicketInfo ticketInfo = checkTicketInfoById(dto.getTicketInfoId());
    if (!ticketInfo.isAvailable()) {
      throw new CustomException(ExceptionType.RESERVATION_UNAVAILABLE_EXCEPTION);
    }
    if (ticketInfo.getLeftSeats() - dto.getCount() < 0) {
      throw new CustomException(ExceptionType.OUT_OF_TICKET_EXCEPTION);
    }
    ticketInfo.minusSeats(dto.getCount());
    ticketInfoRepository.save(ticketInfo);
  }


  // 3. 예매완료
  @Transactional(readOnly = true)
  public ReservationResponseDto reservationComplete(Long resvId, User user) {
    Reservation reservation = checkReservationById(resvId);
    checkReservationUser(reservation, user);
    TicketInfo ticketInfo = checkTicketInfoById(reservation.getTicketInfoId());
    return new ReservationResponseDto(reservation, ticketInfo);
  }

  // 4. 예매취소 여기에도 캐시 처리 함
  @Transactional
  public void deleteReservation(Long resvId, User user) {
    Reservation reservation = checkReservationById(resvId);
    checkReservationUser(reservation, user);
    TicketInfo ticketInfo = checkTicketInfoById(reservation.getTicketInfoId());

//    공연날이 오늘이거나 오늘보다 이전이면 예매 취소 불가능
    LocalDate eventDay = LocalDate.from(ticketInfo.getEvent().getDate());
    if(LocalDate.now().isAfter(eventDay) || LocalDate.now().equals(eventDay)){
      throw new CustomException(ExceptionType.CANCEL_DEADLINE_PASSED_EXCEPTION);
    }

    Integer leftSeats = redisRepository.findLeftSeatsFromRedis(ticketInfo.getId());
    if (leftSeats == null) {
      //    캐시가 없으면 DB를 통해 남은 좌석 수에 추가
      ticketInfo.plusSeats(reservation.getCount());
    } else {
      //    캐시가 있으면 redis에서 남은 좌석 수에 추가
      redisRepository.incrementLeftSeatInRedis(ticketInfo.getId(), reservation.getCount());
    }
    reservationRepository.delete(reservation);
  }

  //  3-1. 예매 기록의 사용자와 현재 토큰상의 사용자 일치 여부 판별 메서드
  private void checkReservationUser(Reservation reservation, User user) {
    if (!reservation.getUser().getId().equals(user.getId())) {
      throw new CustomException(ExceptionType.USER_RESERVATION_NOT_MATCHING_EXCEPTION);
    }
  }
  //  3-2. 예매 ID로 Reservation 확인
  private Reservation checkReservationById(Long reservationId) {
    return reservationRepository.findById(reservationId).orElseThrow(
        () -> new CustomException(ExceptionType.NOT_FOUND_RESERVATION_EXCEPTION)
    );
  }
  //  3-3. ticketInfoId로 TicketInfo 확인
  private TicketInfo checkTicketInfoById(Long ticketInfoId) {
    return ticketInfoRepository.findById(ticketInfoId).orElseThrow(
        () -> new CustomException(ExceptionType.NOT_FOUND_TICKET_INFO_EXCEPTION)
    );
  }

  //  5. ADMIN. DB에서 남은 좌석수만 가져와서 Redis에 (key-value)형태로 저장.
  @Transactional
  public MessageResponseDto saveLeftSeatsInRedis(Long ticketInfoId, User user) {
    checkAdmin(user);
    TicketInfo ticketInfo = checkTicketInfoById(ticketInfoId);
    redisRepository.saveTicketInfoToRedis(ticketInfo);
    return new MessageResponseDto(HttpStatus.CREATED, "redis에 성공적으로 저장되었습니다.");
  }

  //  5-1. user role이 admin인지 체크
  private void checkAdmin(User user) {
    if (!user.getRole().equals(UserRoleEnum.ADMIN)) {
      throw new CustomException(ExceptionType.USER_UNAUTHORIZED_EXCEPTION);
    }
  }

  //  6. ADMIN. 해당하는 공연의 남은 좌석수 Redis에서 삭제(삭제되기전 모든 캐시 DB에 반영)
  @Transactional
  public MessageResponseDto deleteLeftSeatsFromRedis(Long ticketInfoId, User user) {
    checkAdmin(user);
    redisRepository.deleteLeftSeatsInRedis(ticketInfoId);
    return new MessageResponseDto(HttpStatus.OK, "redis에서 캐시가 성공적으로 삭제되었습니다.");
  }

//  7. ADMIN. 현재 Redis에 등록된 모든 LeftSeats의 key를 리스트로 반환
  @Transactional(readOnly = true)
  public List<String> findAllLeftSeatsKeysInRedis(User user) {
    checkAdmin(user);
    return new ArrayList<>(redisRepository.findAllLeftSeatsKeysInRedis());
  }


}


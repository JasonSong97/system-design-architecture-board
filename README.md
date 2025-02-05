# System Design Architecture Board

## 요구사항
- 게시글
  - 게시글 조회, 게시글 생성, 게시글 수정, 게시글 삭제 API
  - 게시글 목록 조회 API(게시판별 최신순)

## 테이블 설계
### article
- article_id | BIGINT | PK
- title | VARCHAR(100) | 제목
- content | VARCHAR(3000) | 내용
- board_id | BIGINT | 게시판 ID(Shard Key)
- writer_id | BIGINT | 작성자 ID
- created_at | DATETIME | 생성시간
- modified_at | DATETIME | 수정시간

## 1. 대규모 시스템 서버 인프라 기초

### 대규모 시스템 서버 인프라 기초
- 클라이언트 요청이 많아지만 서버는 Scale Up 또는 Scale Out 해야한다.
- 만약 Scale Out을 하는 경우 N개의 서버가 생기면서 클라이언트는 각각의 서버 정보를 알아야 한다. 하지만 앞단에 로드밸런서를 두면서 요청 정보를 분산하면서 동시에 클라이언트는 서버의 정보를 알 필요가 없다.
- 물론 Scale Out은 로드밸런서, 서버, 데이터베이스 모두 적용할 수 있다.
- 하지만 클라이언트에서 요청을 보내는 과정이 복잡하면 응답시간이 오래걸린다. 따라서 캐시를 이용할 수 있다.
- N개의 로드밸런서 앞단에 캐시를 두거나, N개의 서버 앞단에 캐시를 두거나, N개의 데이터베이스 앞단에 캐시를 두고 더 빠르게 데이터에 접근할 수 있다.
- 캐시 또한 Scale Out을 적용할 수 있다.
- 만약, 네트워크 문제 또는 자연 재해와 같은 문제로 인해서 로드밸런서, 서버, 데이터베이스 중 1개가 손실될 수 있기 때문에, 여러 장소에 둘 수 있다. 그 결과 거대한 데이터 센터가 만들어진다.
- 애플리케이션이 점차 커지면, 단일 서버의 트래픽이 올라간다. 따라서 리소스 부족, 유지보수 어려움 등의 문제가 생긴다. 따라서 단일 애플리케이션을 각 기능에 맞게 N개의 애플리케이션으로 분리한다. 예를 들면, 결제부분, 주문부문 등등.
- 분리된 애플리케이션이 하나의 서비스로 돌아가려면, 각 서버들은 네트워크 통신을 해야한다. 직접접인 API 통신과 간접접인 이벤트 기반 메시지 통신이 있다.

### 모놀리식
- 모놀리식은 1개의 애플리케이션에 N개의 서비스 기능이 있는 것이다. 따라서 특정 기능에 대한 트래픽이 상승하면 Scale Up을 할 수 있다. 하지만 한계가 있다. 가격이 비싸다. 따라서 Scale Out을 고려할 수 있다.
- 그러면 트래픽이 증가해서 Scale Out을 한 결과, N개의 동일한 서비스를 가진 애플리케이션이 N개가 생겼다. 리소스 낭비다. 왜냐하면 특정 서비스의 기능 부분만 트래픽이 생겼는데, 다른 서비스까지 애플리케이션 자체로 복제를 했기 때문이다.
- 그리고 1개의 애플리케이션은 N개의 서비스 기능이 있기 때문에 1개의 서비스가 에러가 발생하면 모든 애플리케이션은 사용할 수 없다.
- 또한 모놀리식은 빌드와 배포 시, 1개의 애플리케이션 전체를 빌드하고 배포해야 하기 때문에, 만약 내가 특정 기능만 변경 후 빌프와 배포를 하려해도 전체 애플리케이션을 전부 빌드와 배포를 해야하는 단점이 있다. 또한 크기가 커지면서 시간이 매우 오래 걸린다.

### MSA
- 모놀리식 단점을 해결하기 위해, 각 서비스들을 애플리케이션으로 만든다. 따라서 특정 애플리케이션이 에러가 발생해도 다른 애플리케이션에게 영향을 주지 않는다.
- 그리고 특정 애플리케이션만 빌드하고 배포하기 때문에 속도와 시간이 매우 빠르다.
- 또한 특정 서비스의 애플리케이션 트래픽이 증가하면 해당 애플리케이션만 Scale Out 하면 되기 때문에, 낭비되는 리소스가 없다.
- 따라서 확장성은 유연하다. 하지만 서비스(애플리케이션)간의 통신 및 모니터링과 데이터의 일관성을 위한 트랜젝션 관리의 어려움이 존재한다.

### 도커
- 도커 이미지는 실행파일이고, 컨테이너는 실행된 프로세스다. 즉, 이미지는 애플리케이션을 실행할 템플릿이다.
- docker images
- docker pull
- docker ps / docker ps -a
- docker run
- docker start
- docker stop
- docker exec
- docker rm
- docker rmi

---

## 2. 분산 관계형 데이터베이스

- article 서비스에는 CRUD가 있다고 가정하자. 서비스가 활성화가 되면서 단일DB는 부하를 견디지 못한다. 저장해야 할 트래픽이 많아지면 단일DB를 Scale Up을 할 수 있다. 하지만 부담이 커질수록 매번 Scale Up을 할 수 없다.
- DB를 1개 더 늘리는 Scale Out을 하자. 그러면 클라이언트 요청은 분산해서 DB로 들어간다. 샤딩(데이터를 여러DB에 분산하여 저장하는 기술)을 이용해서 데이터를 여러DB에 분산한다. 샤딩된 데이터를 샤드라고한다.

### 수직샤딩, 수평샤딩
- 수직샤딩: 데이터를 수직(컬럼)으로 분할한다. 즉, 왼쪽DB에는 id, article_id 오른쪽DB에는 board_id, created_at이 들어가는 것이다.
  - 장점: 각 샤드가 적은 수의 컬럼을 저장하기 때문에 공간의 이점이 생긴다.
  - 단점: 데이터 분리 때문에 조인 또는 트랜젝션의 어려움이 생긴다.
- 수평샤딩: 왼쪽DB에는 id 1 ~ 5000번까지 넣고, 오른쪽DB에는 5001 ~ 10000번까지 넣는 것이다.
  - 장점: 각 샤드에 데이터가 분산되기 때문에 공간의 이점이 생긴다.
  - 단점: 데이터 분리 때문에 조인 또는 트랜젝션의 어려움이 생긴다.
### 범위 기반 샤딩
- 데이터를 특정 값(샤드 키)의 특정 범위에 따라 분할하는 방법이다. 즉, 수평샤딩과 비슷하다.
- 장점: 만약 id 1 ~ 4000번까지 조회하는 경우 왼쪽DB에서 전부 가져올 수 있다.
- 단점: 데이터가 쏠려있기 때문에 1번 ~ 6000번까지 조회하는 경우 망한다. 
### 해시 기반 샤딩
- 데이터를 특정 값(샤드 키)의 해시함수를 이용해서 분할하는 방법이다. 즉, 골고루 분산한다는 것이다. 
- hash_function = article_id % 2 이런 방법으로 각 DB에 균등하게 넣는다.
- 단점: 목록조회와 같은 범위 데이터 조회에 좋지않다. 1번 ~ 4000번 조회하는 경우를 생각하자. 모든 샤드에 요청을 해야한다.
### 디렉토리 기반 샤딩
- 디렉토리를 사용해서 데이터가 저장된 샤드를 관리하는 방법이다.
- 디렉토리에 아이디와 샤드 키가 명시되어 있어, 데이터를 디렉토리에 맞게 관리한다.
- 디렉토리 관리비용이 존재한다.
### 물리적 샤딩, 논리적 샤딩
- 물리적 샤드
  - 물리적 샤드는 진짜로 DB를 더 생성하는 것이다. DB를 계속 생성하면 샤드 키 기반으로 데이터의 재배치가 필요하다.
  - 또한 클라이언트에서 새롭게 생성되는 샤드의 정보를 알아야한다. 왜냐하면 접근을 해야하기 때문이다.
  - 즉, DB를 확장하면 클라이언트가 DB정보를 알아야하는 불편함이 존재한다.
- 논리적 샤드
  - 1개의 샤드 내부에서 물리적 샤드처럼 분리한다고 가정하는 것이다. 즉 가상의 샤드를 만드는 것이다.
  - 물리적 샤드는 위치를 알 수 있지만, 논리적 샤드는 위치를 알 수 없기 때문에 어떤 물리적 샤드에 위치하는지 알아야 한다. 따라서 라우터가 필요하다.
  - 클라이언트와 DB내부의 샤드들 사이에 샤드 라우터를 만들고, 샤드 라우터 내부에 논리적 샤드와 물리적 샤드를 라우팅하게 만든다.
  - 클라이언트가 논리적 샤드 기반으로 샤드 라우터에 요청을 보내면, 샤드 라우터는 물리적 샤드로 라우팅을 한다.
  - 만약 물리적 샤드가 늘어나는 경우, 추가된 샤드 정보를 샤드 라우터에 등록하면 된다.
### Primary, Replica
- 장애가 발생할 경우 복제본을 사용한다.
- Primary는 주 DB이고, Replica는 복제본이다. Primary에서 Replica로 복제하는 경우는 2개의 방법이 있다.
- 동기적(Sync)으로 복제하면 데이터의 일관성을 유지할 수 있지만, 쓰기 성능이 저하된다.
- 비동기적(Async)으로 복제하면 쓰기 성능은 유지되나 복제본에 최신 데이터가 반영되지 않는다.

### 왜 article_id 대신 board_id가 샤드일까 
- 게시글(article)은 게시판 단위로 서비스를 이용, 따라서 게시판(board_id)단위로 게시글(article) 목록이 조회된다.
- 만약 샤드 키가 article_id면, 1번 board -> (1번 article 왼쪽 샤드) (2번 article 오른쪽 샤드), 따라서 board의 article 목록 조회하는 경우에는 모든 샤드를 조회해서 가져와야 하는 문제가 생긴다.
- 따라서 board_id를 샤드 키로 선정한다. 1번 board -> (1, 2 article 왼쪽 샤드)

### Primary Key - SnowFlake
- 분산 시스템에서 고유한 64비트 ID를 생성하는 알고리즘
- [1비트][41비트:TimeStamp][10비트:노드 ID][12비트:Sequence번호]
- 분산 환경에서도 중복 없이(노드 ID + Sequence 번호) 순차적(TimeStamp)으로 ID 생성

### 대규모 데이터에서의 게시글 목록 조회
- 대규모 데이터에서 목록 조회는 복잡하다. 왜냐하면 모든 데이터를 전부 보여줄 수 없다. 따라서 페이징이 필요하다.
- 모든 데이터를 가져오고 특정 페이지만 추출하는 것은 비효율적이다. 또한 이 경우는 메모리에 모든 데이터가 갈 수 없기 때문에 디스크에서 가져오고 시간이 오래걸린다. 그리고 메모리 용량을 초과할 수 있다.
- 따라서 특정 페이지의 데이터만 바로 추출하는 방법이 필요하다. 그 방법이 페이징 쿼리이다. 방법은 페이지 번호 방식과 무한 스크롤 방식으로 나뉜다.
- 기본 페이징
  - N번 페이지에서 M개의 게시글
  - 게시글의 개수
  - 샤드 키는 board_id이기 때문에 단일 샤드에서 게시글 목록 조회가 가능하다.
  - limit = M개의 게시글
  - offset = (N번 페이지 - 1) * M
  - ```sql
    select * from article
      where board_id = {board_id} // 게시판 별
      order by created_at desc // 최신순
      limit {limit} offset {offset} // N번 페이지에서 M개
    
    // 1번 게시판, 4번 페이지에서 30건의 데이터 조회
    select * from article
      where board_id = 1
      order by created_at desc
      limit 30 offset 90;
    ```
  - `쿼리 시간: 5.70 sec`
  - explain => type: ALL(풀 스캔), extra: Using where(where 조건 필터링); Using filesort(데이터가 많아 디스크에서 데이터를 정렬) => 따라서 인덱스를 사용해야 한다.

### 인덱스
- 관계형 데이터베이스에서는 주로 B+트리로 구성되어 있다. 즉, 데이터가 정렬된 상태로 저장되고, 검색 삽입 삭제 연산이 log 시간에 수행된다. 따라서 트리 구조에서 leaf node 간 연결되기 때문에 범위 검색 효율적이다.
- 따라서 인덱스를 추가하면, 쓰기 시점에 B+트리 구조의 정렬된 상태의 데이터가 생성된다. 
- 이미 인덱스로 지정된 컬럼에 대해 정렬된 상태를 가지고 있기 때문에, 조회 시 전체 데이터를 정렬하고 필터링할 필요가 없다. => 조회쿼리 빠름
- ```sql
  create index idx_board_id_article_id on article(board_id asc, article_id desc);
  ```
- 왜 인덱스에 생성시간이 아닌, article_id가 사용? 왜냐하면 게시글 서비스는 여러 서버로 분산되어 동시에 처리가 가능하다. 따라서 게시글이 동시에 생성될 수 있기 때문이다. 따라서 created_at을 정렬조건으로 하면 순서가 명확하지 않을 수 있다.
- 1200만건 데이터를 넣을 때 멀티스레드로 작업을 했기 때문에 동시에 생성된 데이터가 많을 것이다.
- 그래서 고유한 오름차순을 위해 snow flake가 적용된 article_id를 사용하는 것이다.
- ```sql
  select * from article
    where board_id = {board_id}
    order by article_id desc
    limit {limit} offset {offset};
  ```
- `쿼리 시간: 0.01 sec`
- explain => key: idx_board_id_article_id => 생성된 인덱스가 쿼리에 사용
- 이번에는 50,000 페이지를 조 => limit 30 offset 1499970
- `쿼리 시간: 3.46 sec`
- explain => key: idx_board_id_article_id => 생성된 인덱스가 쿼리에 사용, 쿼리에서 변경된 것은 offset

### 인덱스의 종류
- 클러스터 인덱스(Primary Key), 세컨더리 인덱스를 알아보자. 먼저 MySQL의 기본 스토리지 엔진(DB에서 데이터 저장 및 관리 장치)은 InnoDB 다.
- 그리고 InnoDB는 테이블마다 클러스터 인덱스를 자동 생성한다. Primary Index
- 클러스터드 인덱스는 leaf node에 행 데이터(진짜 데이터)를 가지고 있다. 즉, Primary Key를 이용한 조회는 클러스터 인덱스로 조회를 하고 있는 것이다.
- 우리가 생성한 인덱스는 세컨더리 인덱스다. 세컨더리 인덱스는 인덱스 컬럼 데이터와 데이터에 접근하기 위한 포인터(클러스터 인덱스를 가리키는 포인터, Primary key)를 가지고 있다.
- 따라서 세컨더리 인덱스를 이용환 조회는 세컨더리 인덱스를 통해 Primary Key를 찾고, 클러스터 인덱스(Primary Key)를 사용해서 실제 데이터를 찾는 것이다. 따라서 인덱스 트리를 2번 탄다.
- ```sql
  select * from article
    where board_id = 1
    order by article_id desc
    limit 30 offset 1499970;
  ```
- 위의 쿼리를 보면 30개의 데이터만 필요하지만 offset 0 부터 모든 데이터에 접근하는 단점이 있다. 
- 세컨더리 인덱스는 board_id, article_id를 포함한다. 따라서 세컨더리 인덱스에서 필요한 30건에 대해서 article_id만 먼저 추출한다. 그리고 해당 30건만 클러스터 인덱스에 접근하면 된다.
- ```sql
  select board_id, article_id from article
    where board_id = 1
    order by article_id desc
    limit 30 offset 1499970;
  ```
- `쿼리 시간: 0.36 sec`
- explain => key: idx_board_id_article_id, extra: Using index(인덱스의 데이터만으로 조회를 수행할 수 있는 인덱스, 커버링 인덱스)
- 커버링 인덱스는 클러스터 인덱스를 읽지 않고, 세컨더리 인덱스만 사용 가능한 인덱스
- ```sql
  select * from (
    select article_id from article
        where board_id = 1
        order by article_id desc
        limit 30 offset 1499970
  ) t left join article on t.article_id = article.article_id;
  ```
- `쿼리 시간: 0.28 sec`
- explain => article_id 추출을 위한 서브쿼리에서 파생테이블(DERIVED) 생성, 커버링 인덱스